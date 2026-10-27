package com.zhiflow.mall.api.trade;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 下单防重 token：双击、网络重试、客户端重发都不产生第二张订单，重复提交返回原订单而不是报错。
 *
 * <pre>
 * 发放  GET /mallapi/checkout/token → Redis checkout:{userId}:{token} = EMPTY，30 分钟
 * 提交  Lua 原子：EMPTY → LOCKED（5 分钟）放行；LOCKED → 409「处理中」；O:{订单 id…} → 直接返回原订单；不存在 → 400
 * 结果  成功 → O:{订单 id…}，24 小时；失败 → 恢复 EMPTY，允许重试
 * </pre>
 * token 绑定 userId（键里带），跨用户无效；不替代库存校验。灰度开关 {@code order.checkoutToken.required}：
 * 首个版本 false——没带 token 只记日志照常下单；带了 token 就按上面的规则执行。前端全量一周后改 true。
 */
@Slf4j
@Service
public class CheckoutTokenService {

	static final String KEY = "checkout:";

	static final String EMPTY = "EMPTY";

	static final String LOCKED = "LOCKED";

	static final String DONE_PREFIX = "O:";

	static final Duration ISSUE_TTL = Duration.ofMinutes(30);

	static final Duration LOCK_TTL = Duration.ofMinutes(5);

	static final Duration DONE_TTL = Duration.ofHours(24);

	/** EMPTY → LOCKED 并放行；否则原样返回当前值（LOCKED / O:…）；不存在返回 MISSING。 */
	private static final DefaultRedisScript<String> BEGIN = new DefaultRedisScript<>("""
			local v = redis.call('GET', KEYS[1])
			if not v then return 'MISSING' end
			if v == 'EMPTY' then
			  redis.call('SET', KEYS[1], 'LOCKED', 'EX', tonumber(ARGV[1]))
			  return 'OK'
			end
			return v
			""", String.class);

	private final StringRedisTemplate redis;

	private final Environment env;

	public CheckoutTokenService(StringRedisTemplate redis, Environment env) {
		this.redis = redis;
		this.env = env;
	}

	/** 发放一枚 token（进结算页时取）。 */
	public String issue(String userId) {
		String token = UUID.randomUUID().toString().replace("-", "");
		redis.opsForValue().set(key(userId, token), EMPTY, ISSUE_TTL);
		return token;
	}

	public long issueTtlSeconds() {
		return ISSUE_TTL.getSeconds();
	}

	/** 灰度开关：true = 下单必须带 token。 */
	public boolean required() {
		return Boolean.parseBoolean(env.getProperty("order.checkoutToken.required", "false"));
	}

	/** 一次提交的认领结论。 */
	public enum Verdict {
		/** 放行（本次持有 token） */
		PROCEED,
		/** 同一 token 的上一次提交还在处理中 → 409 */
		IN_PROGRESS,
		/** 同一 token 已下单成功 → 返回原订单 */
		DONE,
		/** token 不存在 / 过期 / 不是本人的 → 400（灰度期不强制时放行） */
		MISSING
	}

	/** @param doneOrderIds 仅 {@link Verdict#DONE} 时非空 */
	public record Claim(Verdict verdict, List<String> doneOrderIds) {
	}

	public Claim begin(String userId, String token) {
		String v = redis.execute(BEGIN, List.of(key(userId, token)), String.valueOf(LOCK_TTL.getSeconds()));
		if ("OK".equals(v)) {
			return new Claim(Verdict.PROCEED, List.of());
		}
		if (LOCKED.equals(v)) {
			return new Claim(Verdict.IN_PROGRESS, List.of());
		}
		if (v != null && v.startsWith(DONE_PREFIX)) {
			return new Claim(Verdict.DONE, Arrays.stream(v.substring(DONE_PREFIX.length()).split(","))
					.filter(StrUtil::isNotBlank).toList());
		}
		return new Claim(Verdict.MISSING, List.of());
	}

	/** 下单成功：记下订单 id（24 小时内同 token 重复提交直接返回这些订单）。 */
	public void complete(String userId, String token, List<String> orderIds) {
		redis.opsForValue().set(key(userId, token), DONE_PREFIX + String.join(",", orderIds), DONE_TTL);
	}

	/** 下单失败：恢复 EMPTY，允许用同一 token 重试。 */
	public void release(String userId, String token) {
		try {
			redis.opsForValue().set(key(userId, token), EMPTY, ISSUE_TTL);
		}
		catch (Exception e) {
			log.warn("下单防重 token 释放失败（用户可刷新结算页取新 token）userId={}", userId, e);
		}
	}

	private static String key(String userId, String token) {
		return KEY + userId + ":" + token;
	}
}
