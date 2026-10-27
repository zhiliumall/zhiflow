package com.zhiflow.mall.api.account;

import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.sms.MockSmsSender;
import com.zhiflow.common.sms.SmsSendException;
import com.zhiflow.common.sms.SmsSender;
import com.zhiflow.mall.api.account.SmsCodeService.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 短信验证码：冷却 / 日限 / 一次性 / 输错作废 / 用途隔离 / 渠道未配置。
 *
 * <p>不用 Mockito：{@link StringRedisTemplate} 是具体类，mock 它会污染 TypeCache；这里用内存版替身。
 */
class SmsCodeServiceTest {

	private final InMemoryRedis redis = new InMemoryRedis();

	private final RecordingSender sender = new RecordingSender();

	private SmsCodeService service;

	@BeforeEach
	void setUp() {
		TenantContextHolder.setTenantId("1");
		service = withSender(sender);
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	@Test
	@DisplayName("发送后 5 分钟内可校验，校验通过即作废（不可重放）")
	void sendThenVerifyOnce() {
		service.send(Scene.LOGIN, "13800000000", "1.1.1.1");
		String code = sender.lastCode();
		assertEquals(6, code.length());

		assertTrue(service.verify(Scene.LOGIN, "13800000000", code));
		assertFalse(service.verify(Scene.LOGIN, "13800000000", code), "验证码用后必须作废");
	}

	@Test
	@DisplayName("用途隔离：登录验证码不能拿去注册")
	void scenesDoNotShareCodes() {
		service.send(Scene.LOGIN, "13800000000", null);
		assertFalse(service.verify(Scene.REGISTER, "13800000000", sender.lastCode()));
	}

	@Test
	@DisplayName("60 秒冷却：冷却期内第二次发送被拒，且没真的发出去")
	void cooldown() {
		service.send(Scene.LOGIN, "13800000000", null);
		assertThrows(BizException.class, () -> service.send(Scene.LOGIN, "13800000000", null));
		assertEquals(1, sender.sent.size());
	}

	@Test
	@DisplayName("每个手机号每天最多 10 条")
	void phoneDailyLimit() {
		for (int i = 0; i < SmsCodeService.PHONE_DAILY_LIMIT; i++) {
			service.send(Scene.LOGIN, "13800000000", null);
			redis.clearCooldowns();
		}
		assertThrows(BizException.class, () -> service.send(Scene.LOGIN, "13800000000", null));
		assertEquals(SmsCodeService.PHONE_DAILY_LIMIT, sender.sent.size());
	}

	@Test
	@DisplayName("每个 IP 每天最多 50 条（换手机号也不行）")
	void ipDailyLimit() {
		for (int i = 0; i < SmsCodeService.IP_DAILY_LIMIT; i++) {
			service.send(Scene.LOGIN, String.format("138%08d", i), "9.9.9.9");
		}
		assertThrows(BizException.class, () -> service.send(Scene.LOGIN, "13900000000", "9.9.9.9"));
	}

	@Test
	@DisplayName("连续输错 5 次作废当前验证码，之后输对也不行")
	void fiveFailsInvalidate() {
		service.send(Scene.LOGIN, "13800000000", null);
		String code = sender.lastCode();
		for (int i = 0; i < SmsCodeService.MAX_FAILS; i++) {
			assertFalse(service.verify(Scene.LOGIN, "13800000000", "000000"));
		}
		assertFalse(service.verify(Scene.LOGIN, "13800000000", code));
	}

	@Test
	@DisplayName("渠道发送失败：撤掉冷却，用户可以马上重试")
	void channelFailureReleasesCooldown() {
		sender.failNext = true;
		assertThrows(BizException.class, () -> service.send(Scene.LOGIN, "13800000000", null));
		service.send(Scene.LOGIN, "13800000000", null);
		assertEquals(1, sender.sent.size());
	}

	@Test
	@DisplayName("手机号格式不对直接拒绝")
	void invalidPhone() {
		assertThrows(BizException.class, () -> service.send(Scene.LOGIN, "1380000", null));
		assertFalse(service.verify(Scene.LOGIN, "1380000", "123456"));
	}

	@Test
	@DisplayName("没配短信渠道：不可用、发送报错、校验恒为 false")
	void disabledWithoutSender() {
		SmsCodeService off = withSender(null);
		assertFalse(off.enabled());
		assertThrows(BizException.class, () -> off.send(Scene.LOGIN, "13800000000", null));
		assertFalse(off.verify(Scene.LOGIN, "13800000000", "123456"));
	}

	@Test
	@DisplayName("只有声明 fixedCode 的 mock 渠道才发 9999，真实渠道一律随机")
	void fixedCodeOnlyForMock() {
		SmsCodeService mock = withSender(new MockSmsSender());
		mock.send(Scene.LOGIN, "13800000001", null);
		assertTrue(mock.verify(Scene.LOGIN, "13800000001", MockSmsSender.FIXED_CODE));

		service.send(Scene.LOGIN, "13800000002", null);
		assertTrue(sender.lastCode().matches("\\d{6}"), "真实渠道发 6 位随机码，不能是固定码");
		assertFalse(service.verify(Scene.LOGIN, "13800000002", MockSmsSender.FIXED_CODE));
	}

	private SmsCodeService withSender(SmsSender smsSender) {
		StaticListableBeanFactory factory = new StaticListableBeanFactory();
		if (smsSender != null) {
			factory.addBean("smsSender", smsSender);
		}
		return new SmsCodeService(factory.getBeanProvider(SmsSender.class), redis);
	}

	/** 记录发出的验证码；可让下一次发送失败。 */
	private static final class RecordingSender implements SmsSender {

		final List<String> sent = new ArrayList<>();

		boolean failNext;

		@Override
		public void sendVerifyCode(String phone, String code) {
			if (failNext) {
				failNext = false;
				throw new SmsSendException("渠道拒绝");
			}
			sent.add(code);
		}

		@Override
		public String provider() {
			return "recording";
		}

		String lastCode() {
			return sent.getLast();
		}
	}

	/** 只实现 SmsCodeService 用到的那几个操作；过期时间不模拟。 */
	private static final class InMemoryRedis extends StringRedisTemplate {

		private final Map<String, String> data = new HashMap<>();

		void clearCooldowns() {
			data.keySet().removeIf(k -> k.startsWith("sms:cool:"));
		}

		@Override
		@SuppressWarnings("unchecked")
		public ValueOperations<String, String> opsForValue() {
			return (ValueOperations<String, String>) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { ValueOperations.class }, (proxy, method, args) -> switch (method.getName()) {
						case "get" -> data.get((String) args[0]);
						case "set" -> {
							data.put((String) args[0], (String) args[1]);
							yield null;
						}
						case "setIfAbsent" -> data.putIfAbsent((String) args[0], (String) args[1]) == null;
						case "increment" -> {
							long n = Long.parseLong(data.getOrDefault((String) args[0], "0")) + 1;
							data.put((String) args[0], Long.toString(n));
							yield n;
						}
						default -> throw new UnsupportedOperationException(method.getName());
					});
		}

		@Override
		public Boolean delete(String key) {
			return data.remove(key) != null;
		}

		@Override
		public Boolean expire(String key, long timeout, TimeUnit unit) {
			return data.containsKey(key);
		}

		@Override
		public Boolean expire(String key, Duration timeout) {
			return data.containsKey(key);
		}
	}
}
