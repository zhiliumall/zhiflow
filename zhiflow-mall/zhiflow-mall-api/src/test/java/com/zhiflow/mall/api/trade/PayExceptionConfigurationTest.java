package com.zhiflow.mall.api.trade;

import com.zhiflow.mall.common.trade.payexception.PayExceptionLoggedDedupe;
import com.zhiflow.mall.common.trade.payexception.PayExceptionRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 不进表差错类型的 Redis 去重：SET NX EX，键带统一前缀、过期时间 = 入池器的窗口；抢到才算首次。
 */
class PayExceptionConfigurationTest {

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> ops = mock(ValueOperations.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	@Test
	@DisplayName("SET NX 抢到 → 首次；没抢到 → 已报过；返回 null（事务 / 管道）→ 按首次，不静默")
	void redisSetIfAbsent() {
		when(redis.opsForValue()).thenReturn(ops);
		String key = "pay:exception:logged:REFUND_STUCK:aftersale:1";
		PayExceptionLoggedDedupe d = PayExceptionConfiguration.loggedDedupe(redis);

		when(ops.setIfAbsent(key, "1", PayExceptionRecorder.LOGGED_DEDUPE_WINDOW)).thenReturn(Boolean.TRUE);
		assertTrue(d.firstSeen("REFUND_STUCK:aftersale:1"));
		when(ops.setIfAbsent(key, "1", PayExceptionRecorder.LOGGED_DEDUPE_WINDOW)).thenReturn(Boolean.FALSE);
		assertFalse(d.firstSeen("REFUND_STUCK:aftersale:1"));
		when(ops.setIfAbsent(key, "1", PayExceptionRecorder.LOGGED_DEDUPE_WINDOW)).thenReturn(null);
		assertTrue(d.firstSeen("REFUND_STUCK:aftersale:1"));
		verify(ops, org.mockito.Mockito.times(3)).setIfAbsent(key, "1", PayExceptionRecorder.LOGGED_DEDUPE_WINDOW);
	}

	@Test
	@DisplayName("没有 Redis → 退回进程内去重（同一笔第二次不报）")
	void noRedisFallsBackToInMemory() {
		PayExceptionLoggedDedupe d = PayExceptionConfiguration.loggedDedupe(null);
		assertTrue(d.firstSeen("k"));
		assertFalse(d.firstSeen("k"));
		assertTrue(d.firstSeen("k2"), "对照：另一笔照常报");
	}

}
