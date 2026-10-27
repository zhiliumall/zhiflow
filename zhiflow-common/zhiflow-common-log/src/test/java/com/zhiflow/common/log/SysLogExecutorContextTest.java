package com.zhiflow.common.log;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.zhiflow.common.log.fallback.AuditFallbackWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * sysLogExecutor 的池线程是复用的：上下文（租户）必须按「提交那一刻」传，而不是按「建线程那一刻」。
 *
 * <p>复现：先在租户 A 的上下文里把核心线程都建出来（实际就是最早几个请求干的），
 * 再在租户 B 的上下文里提交任务 —— 任务里读到的必须是 B。
 * 对照：去掉 {@code setTaskDecorator} 时这里读到的是 A（TTL 只在建线程时按 InheritableThreadLocal 拷贝一次）。
 */
class SysLogExecutorContextTest {

	private static final TransmittableThreadLocal<String> TENANT = new TransmittableThreadLocal<>();

	@Test
	@DisplayName("池线程在租户 A 下建出，之后在租户 B 下提交的任务读到的是 B")
	void contextFollowsSubmitterNotThreadCreator() throws Exception {
		ThreadPoolTaskExecutor executor = LogAutoConfiguration.newSysLogExecutor(mock(AuditFallbackWriter.class));
		try {
			TENANT.set("tenant-A");
			executor.getThreadPoolExecutor().prestartAllCoreThreads();

			TENANT.set("tenant-B");
			for (int i = 0; i < 4; i++) {
				String seen = executor.submit(TENANT::get).get(5, TimeUnit.SECONDS);
				assertThat(seen).as("第 %d 个任务读到的租户", i + 1).isEqualTo("tenant-B");
			}
		}
		finally {
			TENANT.remove();
			executor.shutdown();
		}
	}

}
