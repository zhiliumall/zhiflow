package com.zhiflow.pay.admin.config;

import com.github.binarywang.wxpay.config.WxPayConfig;
import com.github.binarywang.wxpay.service.WxPayService;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.pay.admin.service.PayConfigService;
import com.zhiflow.pay.common.constant.PayConstants;
import com.zhiflow.pay.common.entity.PayConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WxPayService 指纹缓存单测：同指纹同实例 / 指纹字段变重建 / 租户隔离 / 证书文件变重建 / 并发单实例 /
 * 普通商户模式 sub 字段归一化。缓存 Map 是静态的，各用例用唯一 id/mchId 隔离 key，避免互相污染。
 */
class WxPayConfigurationCacheTest {

	private static final AtomicInteger SEQ = new AtomicInteger();

	private PayConfigService payConfigService;

	@BeforeEach
	void setUp() {
		payConfigService = mock(PayConfigService.class);
		new WxPayConfiguration(payConfigService);
		TenantContextHolder.setTenantId("1");
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	private PayConfig newPayConfig() {
		int seq = SEQ.incrementAndGet();
		PayConfig payConfig = new PayConfig();
		payConfig.setId("wx-test-" + seq);
		payConfig.setType(PayConstants.PAY_TYPE_1);
		payConfig.setModel(PayConstants.PAY_MODEL_1);
		payConfig.setAppId("wxAppId-" + seq);
		payConfig.setMchId("mch-" + seq);
		payConfig.setMchKey("mchKey");
		payConfig.setKeyPath("/nonexistent/apiclient_cert.p12");
		payConfig.setPrivateKeyPath("/nonexistent/apiclient_key.pem");
		payConfig.setPrivateCertPath("/nonexistent/apiclient_cert.pem");
		payConfig.setCertSerialNo("serial");
		payConfig.setApiV3Key("apiV3Key");
		return payConfig;
	}

	@Test
	void sameFingerprintReturnsSameInstance() {
		when(payConfigService.findByType(PayConstants.PAY_TYPE_1)).thenReturn(newPayConfig());
		WxPayService first = WxPayConfiguration.getPayService(new WxPayConfig());
		WxPayService second = WxPayConfiguration.getPayService(new WxPayConfig());
		assertSame(first, second);
	}

	@Test
	void participatingFieldChangeRebuildsInstance() {
		PayConfig payConfig = newPayConfig();
		when(payConfigService.findByType(PayConstants.PAY_TYPE_1)).thenReturn(payConfig);
		WxPayService before = WxPayConfiguration.getPayService(new WxPayConfig());

		payConfig.setMchId(payConfig.getMchId() + "-new");
		WxPayService afterMchId = WxPayConfiguration.getPayService(new WxPayConfig());
		assertNotSame(before, afterMchId);
		assertEquals(payConfig.getMchId(), afterMchId.getConfig().getMchId());

		payConfig.setApiV3Key("apiV3Key-new");
		WxPayService afterApiV3Key = WxPayConfiguration.getPayService(new WxPayConfig());
		assertNotSame(afterMchId, afterApiV3Key);

		payConfig.setCertSerialNo("serial-new");
		WxPayService afterSerialNo = WxPayConfiguration.getPayService(new WxPayConfig());
		assertNotSame(afterApiV3Key, afterSerialNo);
	}

	@Test
	void tenantDimensionIsolated() {
		when(payConfigService.findByType(PayConstants.PAY_TYPE_1)).thenReturn(newPayConfig());
		WxPayService tenant1Service = WxPayConfiguration.getPayService(new WxPayConfig());
		TenantContextHolder.setTenantId("2");
		WxPayService tenant2Service = WxPayConfiguration.getPayService(new WxPayConfig());
		assertNotSame(tenant1Service, tenant2Service);
	}

	@Test
	void certFileChangeRebuildsInstance(@TempDir Path tempDir) throws Exception {
		Path certFile = tempDir.resolve("apiclient_cert.p12");
		Files.writeString(certFile, "cert-v1");
		PayConfig payConfig = newPayConfig();
		payConfig.setKeyPath(certFile.toString());
		when(payConfigService.findByType(PayConstants.PAY_TYPE_1)).thenReturn(payConfig);

		WxPayService before = WxPayConfiguration.getPayService(new WxPayConfig());
		// 内容变、路径不变：mtime+size 进指纹须触发重建
		Files.writeString(certFile, "cert-v2-longer");
		Files.setLastModifiedTime(certFile, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
		WxPayService after = WxPayConfiguration.getPayService(new WxPayConfig());
		assertNotSame(before, after);
	}

	@Test
	void normalMerchantModelNormalizesSubFieldsAndSharesInstance() {
		PayConfig payConfig = newPayConfig();
		payConfig.setModel(PayConstants.PAY_MODEL_2);
		when(payConfigService.findByType(PayConstants.PAY_TYPE_1)).thenReturn(payConfig);

		WxPayConfig callerConfigX = new WxPayConfig();
		callerConfigX.setSubMchId("subMchX");
		callerConfigX.setSubAppId("subAppX");
		WxPayService serviceX = WxPayConfiguration.getPayService(callerConfigX);
		// 普通商户模式：等价调用方原「构建后置空」的终态
		assertNull(serviceX.getConfig().getSubMchId());
		assertNull(serviceX.getConfig().getSubAppId());

		WxPayConfig callerConfigY = new WxPayConfig();
		callerConfigY.setSubMchId("subMchY");
		WxPayService serviceY = WxPayConfiguration.getPayService(callerConfigY);
		assertSame(serviceX, serviceY);
	}

	@Test
	void serviceProviderModelKeepsSubMerchantDimension() {
		when(payConfigService.findByType(PayConstants.PAY_TYPE_1)).thenReturn(newPayConfig());

		WxPayConfig callerConfigX = new WxPayConfig();
		callerConfigX.setSubMchId("subMchX");
		WxPayService serviceX = WxPayConfiguration.getPayService(callerConfigX);
		assertEquals("subMchX", serviceX.getConfig().getSubMchId());

		WxPayConfig callerConfigY = new WxPayConfig();
		callerConfigY.setSubMchId("subMchY");
		WxPayService serviceY = WxPayConfiguration.getPayService(callerConfigY);
		assertNotSame(serviceX, serviceY);
		assertEquals("subMchY", serviceY.getConfig().getSubMchId());
	}

	@Test
	void concurrentCallsShareSingleInstance() throws Exception {
		when(payConfigService.findByType(PayConstants.PAY_TYPE_1)).thenReturn(newPayConfig());
		int threads = 20;
		Set<WxPayService> instances = Collections.newSetFromMap(new ConcurrentHashMap<>());
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
						instances.add(WxPayConfiguration.getPayService(new WxPayConfig()));
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
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

	/**
	 * 没配置仍然要响亮地失败，但得是<b>业务失败</b>（2026-09-29）：裸 RuntimeException 在 gRPC 下是
	 * 「未映射的异常 → INTERNAL」，调用方只看到「服务内部错误」；BizException 映射成
	 * FAILED_PRECONDITION + 原文案，REST 下是 200 + R.failed。
	 */
	@Test
	void missingConfigStillFailsLoud() {
		when(payConfigService.findByType(PayConstants.PAY_TYPE_1)).thenReturn(null);
		BizException exception = assertThrows(BizException.class,
				() -> WxPayConfiguration.getPayService(new WxPayConfig()));
		assertEquals("当前租户没有配置微信支付", exception.getMessage());
	}

	@Test
	void blankTenantContextBypassesCache() {
		when(payConfigService.findByType(PayConstants.PAY_TYPE_1)).thenReturn(newPayConfig());
		TenantContextHolder.clear();
		WxPayService first = WxPayConfiguration.getPayService(new WxPayConfig());
		WxPayService second = WxPayConfiguration.getPayService(new WxPayConfig());
		assertNotSame(first, second);
	}
}
