package com.zhiflow.mall.api.account;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.sms.MockSmsSender;
import com.zhiflow.common.sms.SmsSendException;
import com.zhiflow.common.sms.SmsSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * 短信验证码（第二条注册路径与手机号验证码登录）。没有配置短信渠道（没有 {@link SmsSender} Bean）时整体不可用。
 *
 * <p>防刷：同一手机号 60 秒内只能发一次、每天最多 10 次；同一 IP 每天最多 50 次；验证码 5 分钟有效、校验通过即作废，
 * 连续输错 5 次作废当前验证码。发送前还要过图形验证码（由调用方校验）。
 */
@Slf4j
@Service
public class SmsCodeService {

	/** 用途：同一手机号不同用途的验证码互不通用。 */
	public enum Scene {
		LOGIN, REGISTER, BIND_PHONE
	}

	private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");

	static final Duration CODE_TTL = Duration.ofMinutes(5);

	static final Duration COOLDOWN = Duration.ofSeconds(60);

	static final int PHONE_DAILY_LIMIT = 10;

	static final int IP_DAILY_LIMIT = 50;

	static final int MAX_FAILS = 5;

	private final ObjectProvider<SmsSender> smsSender;

	private final StringRedisTemplate redis;

	public SmsCodeService(ObjectProvider<SmsSender> smsSender, StringRedisTemplate redis) {
		this.smsSender = smsSender;
		this.redis = redis;
	}

	/** 是否配置了短信渠道。 */
	public boolean enabled() {
		return smsSender.getIfAvailable() != null;
	}

	public static boolean isPhone(String phone) {
		return phone != null && PHONE.matcher(phone).matches();
	}

	/**
	 * 发验证码。
	 *
	 * @throws BizException 短信未启用、手机号格式不对、发送过频或渠道失败
	 */
	public void send(Scene scene, String phone, String clientIp) {
		SmsSender sender = smsSender.getIfAvailable();
		if (sender == null) {
			throw new BizException("未启用短信验证码");
		}
		if (!isPhone(phone)) {
			throw new BizException("手机号格式不正确");
		}
		String tenant = TenantContextHolder.getTenantId();
		if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key("cool", tenant, phone), "1", COOLDOWN))) {
			throw new BizException("验证码发送过频繁，请稍后再试");
		}
		String day = LocalDate.now().toString();
		if (incrWithin(key("day", tenant, day + ":" + phone)) > PHONE_DAILY_LIMIT
				|| (StrUtil.isNotBlank(clientIp) && incrWithin(key("ip", tenant, day + ":" + clientIp)) > IP_DAILY_LIMIT)) {
			throw new BizException("今日验证码发送次数已达上限");
		}
		String code = sender.fixedCode() ? MockSmsSender.FIXED_CODE : RandomUtil.randomNumbers(6);
		try {
			sender.sendVerifyCode(phone, code);
		}
		catch (SmsSendException e) {
			redis.delete(key("cool", tenant, phone));
			throw new BizException("验证码发送失败，请稍后重试");
		}
		redis.opsForValue().set(codeKey(scene, tenant, phone), code, CODE_TTL);
		redis.delete(failKey(scene, tenant, phone));
	}

	/**
	 * 校验验证码，通过即作废（一次性）。
	 *
	 * @return true = 正确
	 */
	public boolean verify(Scene scene, String phone, String code) {
		if (!enabled() || !isPhone(phone) || StrUtil.isBlank(code)) {
			return false;
		}
		String tenant = TenantContextHolder.getTenantId();
		String codeKey = codeKey(scene, tenant, phone);
		String saved = redis.opsForValue().get(codeKey);
		if (saved == null) {
			return false;
		}
		if (!saved.equals(code.trim())) {
			Long fails = redis.opsForValue().increment(failKey(scene, tenant, phone));
			redis.expire(failKey(scene, tenant, phone), CODE_TTL);
			if (fails != null && fails >= MAX_FAILS) {
				redis.delete(codeKey);
			}
			return false;
		}
		redis.delete(codeKey);
		redis.delete(failKey(scene, tenant, phone));
		return true;
	}

	private long incrWithin(String key) {
		Long n = redis.opsForValue().increment(key);
		if (n != null && n == 1L) {
			redis.expire(key, Duration.ofDays(1));
		}
		return n == null ? 0 : n;
	}

	private static String codeKey(Scene scene, String tenant, String phone) {
		return key("code:" + scene.name(), tenant, phone);
	}

	private static String failKey(Scene scene, String tenant, String phone) {
		return key("fail:" + scene.name(), tenant, phone);
	}

	private static String key(String kind, String tenant, String suffix) {
		return "sms:" + kind + ":" + tenant + ":" + suffix;
	}
}
