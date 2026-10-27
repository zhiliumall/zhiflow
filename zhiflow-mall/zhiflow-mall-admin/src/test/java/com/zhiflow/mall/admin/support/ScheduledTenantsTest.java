package com.zhiflow.mall.admin.support;

import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.common.observability.JobFailedException;
import com.zhiflow.upms.common.dto.TenantInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [r3 followup C1] 逐租户定时任务的租户清单只在一处判定成败：{@link ScheduledTenants#listOrFail}。
 */
class ScheduledTenantsTest {

	private static TenantInfo tenant(String id) {
		TenantInfo t = new TenantInfo();
		t.setId(id);
		return t;
	}

	@Test
	@DisplayName("传输失败 / 本地熔断 / 业务拒绝 / 无响应 ⇒ 抛 JobFailedException（本轮跳过且记 failure），不是空清单")
	void everyFailureShapeFailsTheRun() {
		for (String errCode : new String[] {"remote.unavailable", null}) {
			R<List<TenantInfo>> failed = R.failed("down");
			failed.setErrCode(errCode);
			JobFailedException e = assertThrows(JobFailedException.class,
					() -> ScheduledTenants.listOrFail(failed, "RewardSettlementTask"));
			assertTrue(e.getMessage().contains("RewardSettlementTask"), e.getMessage());
		}
		assertThrows(JobFailedException.class, () -> ScheduledTenants.listOrFail(null, "RewardSettlementTask"));
	}

	@Test
	@DisplayName("对照：查询成功 ⇒ 原样返回；成功但为空 / data 为 null ⇒ 空清单（真实答复）")
	void successIsReturnedAsIs() {
		List<TenantInfo> tenants = List.of(tenant("1"), tenant("2"));
		assertSame(tenants, ScheduledTenants.listOrFail(R.ok(tenants), "t"));
		assertEquals(List.of(), ScheduledTenants.listOrFail(R.ok(List.of()), "t"));
		assertEquals(List.of(), ScheduledTenants.listOrFail(R.ok(null), "t"));
	}

	/**
	 * 门禁：mall-admin 主代码里不许再出现「租户清单直接 getData()」。新写的逐租户任务照抄老任务时最容易把它带回来，
	 * 而它的后果（失败当成 0 个租户、任务记 success）在单测和监控上都看不出来。
	 */
	@Test
	@DisplayName("门禁：主代码里没有 tenantApi.list(..).getData() —— 一律经 ScheduledTenants.listOrFail")
	void noRawTenantListGetDataInMainSources() throws IOException {
		Pattern raw = Pattern.compile("\\.list\\(\\s*SecurityConstants\\.FROM_IN\\s*\\)\\s*\\.getData\\(\\)");
		List<String> offenders = new ArrayList<>();
		Path root = Path.of("src/main/java");
		try (Stream<Path> files = Files.walk(root)) {
			for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				String src = Files.readString(f, StandardCharsets.UTF_8);
				if (raw.matcher(src).find()) {
					offenders.add(root.relativize(f).toString());
				}
			}
		}
		// 判据自检：扫描根下确实有源码，正则也确实认得这种写法（否则上面恒为空、门禁恒绿）
		assertTrue(Files.isDirectory(root.resolve("com/zhiflow/mall/admin/service")), "扫描根不对");
		assertTrue(raw.matcher("tenantApi.list(SecurityConstants.FROM_IN).getData();").find(), "正则认不出被禁的写法");
		assertTrue(offenders.isEmpty(), () -> "这些文件仍把租户清单查询失败当成空清单：" + offenders);
	}
}
