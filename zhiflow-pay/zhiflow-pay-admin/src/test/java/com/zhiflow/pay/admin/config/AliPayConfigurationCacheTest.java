package com.zhiflow.pay.admin.config;

import com.alipay.easysdk.factory.MultipleFactory;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.pay.admin.service.PayConfigService;
import com.zhiflow.pay.common.constant.PayConstants;
import com.zhiflow.pay.common.entity.PayConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MultipleFactory 指纹缓存单测：同指纹同实例 / 指纹字段变重建 / 租户隔离 / 并发单实例 /
 * 构造抛错（证书文件缺失）不落缓存且指纹修复后可恢复。
 */
class AliPayConfigurationCacheTest {

	private static final AtomicInteger SEQ = new AtomicInteger();

	private PayConfigService payConfigService;

	@BeforeEach
	void setUp() {
		payConfigService = mock(PayConfigService.class);
		new AliPayConfiguration(payConfigService);
		TenantContextHolder.setTenantId("1");
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	/**
	 * 三个证书路径留空 → easysdk Context 跳过证书解析，可在无真证书的单测环境构建成功
	 */
	private PayConfig newPayConfig() {
		int seq = SEQ.incrementAndGet();
		PayConfig payConfig = new PayConfig();
		payConfig.setId("ali-test-" + seq);
		payConfig.setType(PayConstants.PAY_TYPE_2);
		payConfig.setModel(PayConstants.PAY_MODEL_1);
		payConfig.setAppId("aliAppId-" + seq);
		payConfig.setMchKey("merchantPrivateKey");
		return payConfig;
	}

	@Test
	void sameFingerprintReturnsSameInstance() throws Exception {
		when(payConfigService.findByType(PayConstants.PAY_TYPE_2)).thenReturn(newPayConfig());
		MultipleFactory first = AliPayConfiguration.getFactory();
		MultipleFactory second = AliPayConfiguration.getFactory();
		assertSame(first, second);
	}

	@Test
	void participatingFieldChangeRebuildsInstance() throws Exception {
		PayConfig payConfig = newPayConfig();
		when(payConfigService.findByType(PayConstants.PAY_TYPE_2)).thenReturn(payConfig);
		MultipleFactory before = AliPayConfiguration.getFactory();

		payConfig.setAppId(payConfig.getAppId() + "-new");
		MultipleFactory afterAppId = AliPayConfiguration.getFactory();
		assertNotSame(before, afterAppId);
		assertEquals(payConfig.getAppId(), afterAppId.context.getConfig("appId"));

		payConfig.setMchKey("merchantPrivateKey-new");
		MultipleFactory afterMchKey = AliPayConfiguration.getFactory();
		assertNotSame(afterAppId, afterMchKey);
	}

	@Test
	void tenantDimensionIsolated() throws Exception {
		when(payConfigService.findByType(PayConstants.PAY_TYPE_2)).thenReturn(newPayConfig());
		MultipleFactory tenant1Factory = AliPayConfiguration.getFactory();
		TenantContextHolder.setTenantId("2");
		MultipleFactory tenant2Factory = AliPayConfiguration.getFactory();
		assertNotSame(tenant1Factory, tenant2Factory);
	}

	@Test
	void constructionFailureNotCachedAndRecoverable() throws Exception {
		PayConfig payConfig = newPayConfig();
		// alipayCertPath（← privateKeyPath）非空且文件不存在 → setOptions 构造 Context 解析证书抛错
		payConfig.setPrivateKeyPath("/nonexistent/alipayCertPublicKey_RSA2.crt");
		when(payConfigService.findByType(PayConstants.PAY_TYPE_2)).thenReturn(payConfig);

		assertThrows(Exception.class, AliPayConfiguration::getFactory);
		// 再试同一配置仍抛错（而非返回 null/半初始化实例）→ 证明坏实例没落缓存
		assertThrows(Exception.class, AliPayConfiguration::getFactory);

		// 修复配置（指纹变）后可正常构建
		payConfig.setPrivateKeyPath(null);
		MultipleFactory recovered = AliPayConfiguration.getFactory();
		assertNotNull(recovered);
		assertSame(recovered, AliPayConfiguration.getFactory());
	}

	@Test
	void concurrentCallsShareSingleInstance() throws Exception {
		when(payConfigService.findByType(PayConstants.PAY_TYPE_2)).thenReturn(newPayConfig());
		int threads = 20;
		Set<MultipleFactory> instances = Collections.newSetFromMap(new ConcurrentHashMap<>());
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			for (int i = 0; i < threads; i++) {
				executor.submit(() -> {
					ready.countDown();
					try {
						start.await();
						// TenantContextHolder 是 TransmittableThreadLocal，子线程需自行设置
						TenantContextHolder.setTenantId("1");
						instances.add(AliPayConfiguration.getFactory());
					} catch (Exception e) {
						throw new IllegalStateException(e);
					} finally {
						TenantContextHolder.clear();
					}
				});
			}
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			executor.shutdown();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}
		assertEquals(1, instances.size());
	}

	/** 同 WxPayConfigurationCacheTest#missingConfigStillFailsLoud：要响亮，但得是业务失败（2026-09-29）。 */
	@Test
	void missingConfigStillFailsLoud() {
		when(payConfigService.findByType(PayConstants.PAY_TYPE_2)).thenReturn(null);
		BizException exception = assertThrows(BizException.class, AliPayConfiguration::getFactory);
		assertEquals("当前租户没有配置支付宝", exception.getMessage());
	}
}
