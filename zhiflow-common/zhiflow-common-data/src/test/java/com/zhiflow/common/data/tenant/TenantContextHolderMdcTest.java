package com.zhiflow.common.data.tenant;

import com.alibaba.ttl.TtlRunnable;
import com.zhiflow.common.core.observability.TraceIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 租户上下文与日志上下文同步：set 写 MDC tenant.id，clear / pop 必须移除
 * （线程复用时「缺失」可以接受，「顶着上一个租户」不行）。
 */
class TenantContextHolderMdcTest {

	@AfterEach
	void cleanup() {
		TenantContextHolder.clear();
		MDC.clear();
	}

	@Test
	@DisplayName("set → MDC 有值；clear → MDC 移除")
	void setAndClear() {
		TenantContextHolder.setTenantId("1000000000000000002");
		assertEquals("1000000000000000002", MDC.get(TraceIds.MDC_TENANT_ID));

		TenantContextHolder.clear();
		assertNull(MDC.get(TraceIds.MDC_TENANT_ID));
		assertNull(TenantContextHolder.getTenantId());
	}

	@Test
	@DisplayName("pop 与 clear 一样移除；设成空串等同清除")
	void popAndBlank() {
		TenantContextHolder.setTenantId("1");
		TenantContextHolder.pop();
		assertNull(MDC.get(TraceIds.MDC_TENANT_ID));

		TenantContextHolder.setTenantId("1");
		TenantContextHolder.setTenantId("");
		assertNull(MDC.get(TraceIds.MDC_TENANT_ID));
	}

	@Test
	@DisplayName("异步任务里 setTenantId 却没 clear：TTL 还原租户后，池线程的 MDC 也不能留着那个租户号")
	void ttlTaskSettingTenantWithoutClearDoesNotLeakMdc() throws Exception {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			// 与 auth 登录日志的异步任务同形：提交方没有租户，任务里设了、不清
			pool.submit(TtlRunnable.get(() -> TenantContextHolder.setTenantId("1"))).get();

			String[] seen = new String[2];
			pool.submit(TtlRunnable.get(() -> {
				seen[0] = TenantContextHolder.getTenantId();
				seen[1] = MDC.get(TraceIds.MDC_TENANT_ID);
			})).get();
			assertNull(seen[0], "对照：租户上下文本身已被 TTL 还原");
			assertNull(seen[1], "MDC 不能顶着上一个任务的租户号");
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("异步线程的日志行带上提交方的租户；任务结束后池线程恢复为空")
	void ttlReplaysAndRestoresMdc() throws Exception {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			TenantContextHolder.setTenantId("1000000000000000002");
			String[] seen = new String[1];
			pool.submit(TtlRunnable.get(() -> seen[0] = MDC.get(TraceIds.MDC_TENANT_ID))).get();
			assertEquals("1000000000000000002", seen[0]);

			TenantContextHolder.clear();
			pool.submit(TtlRunnable.get(() -> seen[0] = MDC.get(TraceIds.MDC_TENANT_ID))).get();
			assertNull(seen[0], "提交方没有租户时，池线程上不残留上一个任务的值");
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("调用方线程自己执行任务（CallerRuns / 帮忙执行）：任务改了租户，回来后 MDC 还原成调用方的")
	void callerRunsRestoresCallerMdc() {
		TenantContextHolder.setTenantId("1");
		Runnable task = TtlRunnable.get(() -> TenantContextHolder.setTenantId("900000000000000001"));
		task.run();
		assertEquals("1", TenantContextHolder.getTenantId(), "对照：租户上下文本身由 TTL 还原");
		assertEquals("1", MDC.get(TraceIds.MDC_TENANT_ID));
	}

	@Test
	@DisplayName("公网回调里任意可填的超长租户号：MDC 截断到 32 字符，租户上下文本身原样不动")
	void longValueTruncatedInMdcOnly() {
		String junk = "x".repeat(200);
		TenantContextHolder.setTenantId(junk);
		assertEquals(32, MDC.get(TraceIds.MDC_TENANT_ID).length());
		assertEquals(junk, TenantContextHolder.getTenantId());
	}

}
