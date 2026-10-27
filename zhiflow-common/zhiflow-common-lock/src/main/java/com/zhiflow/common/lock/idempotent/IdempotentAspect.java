package com.zhiflow.common.lock.idempotent;

import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;

/**
 * 提交幂等切面。
 *
 * <h2>状态机</h2>
 * <pre>
 *              SETNX idem:{key} = "PROCESSING"  TTL 60s
 *                        │
 *        ┌───────────────┴───────────────┐
 *   成功 │                                │ 失败（key 已存在）
 *        ▼                                ▼
 *   执行业务方法                      GET idem:{key}
 *        │                                │
 *        │                    ┌───────────┴───────────┐
 * 成功 ─ ▼ ─ 异常             │ "PROCESSING"          │ 已是序列化响应
 * SET=响应  DEL key           ▼                       ▼
 * (保留TTL) (允许重试)   「请勿重复提交，          原样重放首次响应
 *                        上一次仍在处理中」
 * </pre>
 *
 * <h2>三个取舍</h2>
 * <ul>
 *   <li><b>业务抛异常时删 key</b>：让用户能立刻重试。若保留，用户 60 秒内无法重新提交一个
 *       本来就失败了的操作——体验极差，且失败的操作重放没有防重价值。</li>
 *   <li><b>成功时保留剩余 TTL 而不是重置</b>：避免慢请求把幂等窗口无限延长。</li>
 *   <li><b>Redis 故障降级放行</b>：幂等是防重不是鉴权，不该成为新的单点。</li>
 * </ul>
 */
@Slf4j
@Aspect
@RequiredArgsConstructor
public class IdempotentAspect implements Ordered {

	/** 约定的请求头名 */
	public static final String HEADER = "Idempotency-Key";

	/**
	 * 占位哨兵：表示首次请求正在执行、尚无响应可重放。
	 * 缓存的响应一律是 JSON 对象（以 { 开头），故不会与本值相撞。
	 */
	static final String PROCESSING = "__PROCESSING__";

	private final StringRedisTemplate redisTemplate;

	private final IdempotentProperties properties;

	private final ObjectMapper objectMapper;

	/**
	 * 排在事务与分布式锁之前：幂等命中时应当连业务方法都不进，
	 * 没必要为一个要被丢弃的调用开事务/抢锁。
	 */
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 10;
	}

	@Around("@annotation(idempotent)")
	public Object around(ProceedingJoinPoint point, Idempotent idempotent) throws Throwable {
		if (!properties.isEnabled()) {
			return point.proceed();
		}

		String token = header();
		if (token == null || token.isBlank()) {
			if (requireKey(idempotent)) {
				throw new IdempotentException("缺少 " + HEADER + " 请求头");
			}
			// 前端拦截器尚未上线时的默认路径：不做幂等，业务照常
			return point.proceed();
		}

		String key = buildKey(token);
		Duration ttl = Duration.ofSeconds(idempotent.ttlSeconds() > 0
				? idempotent.ttlSeconds() : properties.getTtlSeconds());

		Boolean acquired;
		try {
			acquired = redisTemplate.opsForValue().setIfAbsent(key, PROCESSING, ttl);
		}
		catch (Exception e) {
			// 降级：valkey 抖一下就让退款/短信全部 500，是用小概率问题换大概率事故
			log.error("幂等检查失败，降级放行 key={}", key, e);
			return point.proceed();
		}

		if (!Boolean.TRUE.equals(acquired)) {
			return replayOrReject(key);
		}

		try {
			Object result = point.proceed();
			cacheQuietly(key, result);
			return result;
		}
		catch (Throwable t) {
			// 失败即释放，让用户能立刻重试；失败的操作重放没有防重价值
			deleteQuietly(key);
			throw t;
		}
	}

	/** key 已存在：要么重放首次响应，要么告知仍在处理中 */
	private Object replayOrReject(String key) {
		String cached;
		try {
			cached = redisTemplate.opsForValue().get(key);
		}
		catch (Exception e) {
			log.error("读取幂等缓存失败 key={}", key, e);
			throw new IdempotentException("请勿重复提交");
		}

		if (cached == null) {
			// 罕见：SETNX 之后到 GET 之间恰好过期。当作首次冲突处理，让用户重试
			throw new IdempotentException("请勿重复提交");
		}
		if (PROCESSING.equals(cached)) {
			throw new IdempotentException("请勿重复提交，上一次请求仍在处理中");
		}

		try {
			IdempotentResponse envelope = objectMapper.readValue(cached, IdempotentResponse.class);
			return objectMapper.readValue(envelope.getBody(), Class.forName(envelope.getType()));
		}
		catch (Exception e) {
			// 反序列化失败也降级：拿不回首次响应时退回提示，而不是抛一个看不懂的异常
			log.warn("幂等响应重放失败，降级为提示 key={}", key, e);
			throw new IdempotentException("请勿重复提交");
		}
	}

	/**
	 * 缓存响应。<b>保留剩余 TTL</b>（{@code KEEPTTL} 语义）——用 SET 不带过期时间会让 key
	 * 变成永不过期，用 SET 带新 TTL 又会把窗口延长，两者都不对。
	 */
	private void cacheQuietly(String key, Object result) {
		if (result == null) {
			return;
		}
		try {
			Long remaining = redisTemplate.getExpire(key);
			if (remaining == null || remaining <= 0) {
				return;   // 已过期，无需缓存
			}
			IdempotentResponse envelope = new IdempotentResponse(
					result.getClass().getName(), objectMapper.writeValueAsString(result));
			redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(envelope),
					Duration.ofSeconds(remaining));
		}
		catch (Exception e) {
			// 缓存失败不影响本次业务——最坏结果是重复请求拿到「请勿重复提交」而不是原响应
			log.warn("缓存幂等响应失败 key={}", key, e);
		}
	}

	private void deleteQuietly(String key) {
		try {
			redisTemplate.delete(key);
		}
		catch (Exception e) {
			log.warn("释放幂等 key 失败 key={}，将等待 TTL 自然过期", key, e);
		}
	}

	private boolean requireKey(Idempotent idempotent) {
		return switch (idempotent.required()) {
			case YES -> true;
			case NO -> false;
			case FOLLOW_GLOBAL -> properties.isRequireKey();
		};
	}

	/**
	 * key 含 serviceId 与 userId：
	 * serviceId 隔离服务避免同名 key 互撞；userId 防止 A 用户的 key 挡住 B 用户
	 * （客户端生成的 UUID 理论上不会撞，但不能依赖客户端不作恶）。
	 */
	private String buildKey(String token) {
		return String.join(":", properties.getKeyPrefix(), serviceId(), userId(), token);
	}

	private String serviceId() {
		String name = System.getProperty("spring.application.name");
		return name == null ? "unknown" : name;
	}

	private String userId() {
		HttpServletRequest request = currentRequest();
		if (request == null) {
			return "anonymous";
		}
		Object userId = request.getAttribute("userId");
		return userId == null ? "anonymous" : String.valueOf(userId);
	}

	private String header() {
		HttpServletRequest request = currentRequest();
		return request == null ? null : request.getHeader(HEADER);
	}

	private HttpServletRequest currentRequest() {
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
			return attributes.getRequest();
		}
		return null;
	}
}
