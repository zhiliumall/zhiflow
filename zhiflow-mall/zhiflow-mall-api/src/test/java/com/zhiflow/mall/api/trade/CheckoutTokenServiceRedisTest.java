package com.zhiflow.mall.api.trade;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.net.Socket;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收「同 token 并发 20 次下单只产生 1 张订单」——真 Redis 跑 Lua（本地起 {@code redis-server --port 56379}；
 * 连不上则跳过，CI 无 Redis 时不误报）。
 */
class CheckoutTokenServiceRedisTest {

	private static LettuceConnectionFactory factory;

	private static StringRedisTemplate redis;

	@BeforeAll
	static void connect() {
		boolean up;
		try (Socket s = new Socket("127.0.0.1", 56379)) {
			up = s.isConnected();
		}
		catch (Exception e) {
			up = false;
		}
		Assumptions.assumeTrue(up, "本地没有 127.0.0.1:56379 的 Redis，跳过");
		factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 56379));
		factory.afterPropertiesSet();
		factory.start();
		redis = new StringRedisTemplate(factory);
		redis.afterPropertiesSet();
	}

	@AfterAll
	static void close() {
		if (factory != null) {
			factory.destroy();
		}
	}

	@Test
	@DisplayName("同 token 并发 20 次：只放行 1 次，其余 19 次 409 或（完成后）拿到同一张订单")
	void twentyConcurrentSubmitsCreateOneOrder() throws Exception {
		CheckoutTokenService svc = new CheckoutTokenService(redis, new MockEnvironment());
		String token = svc.issue("u1");
		AtomicInteger created = new AtomicInteger();
		ConcurrentLinkedQueue<String> verdicts = new ConcurrentLinkedQueue<>();
		ExecutorService pool = Executors.newFixedThreadPool(20);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(20);
		for (int i = 0; i < 20; i++) {
			pool.execute(() -> {
				try {
					start.await();
					CheckoutTokenService.Claim c = svc.begin("u1", token);
					verdicts.add(c.verdict().name());
					if (c.verdict() == CheckoutTokenService.Verdict.PROCEED) {
						created.incrementAndGet();
						Thread.sleep(50);
						svc.complete("u1", token, List.of("ORDER-1"));
					}
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		assertTrue(done.await(10, TimeUnit.SECONDS));
		pool.shutdown();
		assertEquals(1, created.get(), "只产生 1 张订单");
		assertEquals(19, verdicts.stream().filter(v -> v.equals("IN_PROGRESS") || v.equals("DONE")).count());
		CheckoutTokenService.Claim again = svc.begin("u1", token);
		assertEquals(CheckoutTokenService.Verdict.DONE, again.verdict());
		assertEquals(List.of("ORDER-1"), again.doneOrderIds(), "重复提交返回原订单");
	}

	@Test
	@DisplayName("失败恢复 EMPTY 可重试；token 绑用户（别人拿去无效）；没发过的 token → MISSING")
	void releaseAndBinding() {
		CheckoutTokenService svc = new CheckoutTokenService(redis, new MockEnvironment());
		String token = svc.issue("u2");
		assertEquals(CheckoutTokenService.Verdict.MISSING, svc.begin("someone-else", token).verdict());
		assertEquals(CheckoutTokenService.Verdict.PROCEED, svc.begin("u2", token).verdict());
		svc.release("u2", token);
		assertEquals(CheckoutTokenService.Verdict.PROCEED, svc.begin("u2", token).verdict(), "下单失败后同一 token 可以重试");
		assertEquals(CheckoutTokenService.Verdict.MISSING, svc.begin("u2", "never-issued").verdict());
	}
}
