package com.zhiflow.mall.api.schema;

import com.zhiflow.common.test.schema.EntityColumnAudit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商城实体映射的每一列都必须由版本化迁移建出——<b>mall-api 类路径上的那一半</b>（r3 followup C7，2026-10-06）。
 *
 * <p>mall-admin 的 {@code MallEntityColumnMigrationGateTest} 只看得到 mall-admin（含 mall-common）的实体；
 * 这里用同一份判据（{@link EntityColumnAudit}）、同一批迁移（mall-admin 的 {@code db/migration/mall}，{@code MallTenantMigrator}
 * 只跑它）核对 mall-api 类路径上的实体。迁移在 mall-admin 模块里，按 reactor 里的相对路径读（surefire 的工作目录是本模块根）。
 */
class MallApiEntityColumnMigrationGateTest {

	private static final Path MIGRATIONS = Path.of("../zhiflow-mall-admin/src/main/resources/db/migration/mall");

	private static Map<String, Map<String, String>> entityColumns;

	private static Map<String, Set<String>> migrationColumns;

	@BeforeAll
	static void load() {
		entityColumns = EntityColumnAudit.entityColumns("com.zhiflow.mall");
		migrationColumns = EntityColumnAudit.migrationColumns(MIGRATIONS);
	}

	@Test
	void everyEntityColumnIsCreatedByAVersionedMigration() {
		List<String> missing = EntityColumnAudit.unversionedColumns(entityColumns, migrationColumns, Set.of());
		assertTrue(missing.isEmpty(), () -> """
				mall-api 的实体映射了 %d 个没有任何版本化迁移建出来的列（一发版 BaseMapper 的 SQL 就报「列不存在」）：
				%s

				在 mall-admin 加一个 db/migration/mall/V{N}__*.sql（ALTER TABLE … ADD COLUMN IF NOT EXISTS …）。
				""".formatted(missing.size(), String.join("\n", missing)));
	}

	/** 自检：真的扫到了 mall-api 的实体、真的读到了 mall-admin 的迁移（否则上面那条是恒绿的）。 */
	@Test
	void theScanActuallySeesMallApiEntitiesAndTheMigrations() {
		for (String table : List.of("order_info", "banner", "global_hot_word")) {
			assertTrue(entityColumns.containsKey(table), () -> "没扫到 mall-api 的实体表 " + table + "，扫到的：" + entityColumns.keySet());
		}
		assertTrue(entityColumns.getOrDefault("banner", Map.of()).containsKey("image_url"), "Banner#imageUrl 应映射到 image_url");
		assertTrue(migrationColumns.getOrDefault("banner", Set.of()).contains("image_url"), "认不出 V1 基线里轮播图表的列定义");
		assertTrue(migrationColumns.getOrDefault("order_info", Set.of()).contains("order_no"), "认不出 V1 基线里订单表的列定义");
	}
}
