package com.zhiflow.mall.admin.config;

import com.zhiflow.common.test.schema.EntityColumnAudit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商城实体映射的每一列，都必须有<b>版本化迁移</b>建出来（r3-B2，2026-10-06；开源分支阶段 6 起基线 V1 建齐全部列，不再有存量清单）。
 *
 * <h2>为什么</h2>
 * <p>MyBatis-Plus 的 {@code BaseMapper} 会显式列出实体全部字段。实体加了字段、迁移没建列，一发版相关 SQL 就报「列不存在」，
 * 而编译、单测都是绿的：没有任何判据把「实体字段」和「库里真有的列」放在一起比。
 *
 * <h2>判据</h2>
 * <p>实体列 = 用 MyBatis-Plus 自己的 {@link TableInfoHelper} 算出来的列（与运行时生成 SQL 同一套规则），扫 {@code com.zhiflow.mall}
 * 下全部 {@code @TableName}；每一列都必须被某个 {@code db/migration/mall/V*.sql} 建出来（{@code CREATE TABLE} 的列定义或
 * {@code ALTER TABLE … ADD COLUMN}）。
 *
 * <p>本门禁扫的是 mall-admin 类路径上的实体（含 mall-common）；只在 mall-api 里的实体由 mall-api 的
 * {@code MallApiEntityColumnMigrationGateTest} 用同一份判据（{@link EntityColumnAudit}）核对。
 */
class MallEntityColumnMigrationGateTest {

	private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration/mall");

	private static final String ENTITY_PACKAGE = "com.zhiflow.mall";

	/** table → 实体列（小写），值里带「哪个实体类」便于报错。 */
	private static Map<String, Map<String, String>> entityColumns;

	private static Map<String, Set<String>> migrationColumns;

	@BeforeAll
	static void load() {
		entityColumns = EntityColumnAudit.entityColumns(ENTITY_PACKAGE);
		migrationColumns = EntityColumnAudit.migrationColumns(MIGRATIONS);
	}

	@Test
	void everyEntityColumnIsCreatedByAVersionedMigration() {
		List<String> missing = EntityColumnAudit.unversionedColumns(entityColumns, migrationColumns, Set.of());
		assertTrue(missing.isEmpty(), () -> """
				实体映射了 %d 个没有任何版本化迁移建出来的列（一发版 BaseMapper 的 SQL 就报「列不存在」）：
				%s

				加一个 db/migration/mall/V{N}__*.sql（ALTER TABLE … ADD COLUMN IF NOT EXISTS …）。MallTenantMigrator 只跑 V*.sql。
				""".formatted(missing.size(), String.join("\n", missing)));
	}

	/** 自检：解析器真的认得建列写法，实体扫描真的扫到了东西（否则上面那条是恒绿的）。 */
	@Test
	void theParsersActuallyWork() {
		assertTrue(entityColumns.size() > 25, () -> "只扫到 " + entityColumns.size() + " 张实体表 —— 扫描包或类路径不对");
		assertTrue(entityColumns.getOrDefault("order_info", Map.of()).containsKey("payment_price"),
				"OrderInfo.paymentPrice 应映射到 order_info.payment_price");
		assertTrue(migrationColumns.getOrDefault("order_info", Set.of()).contains("order_no"), "认不出 V1 基线里 CREATE TABLE 的列定义");
		assertTrue(migrationColumns.getOrDefault("banner", Set.of()).contains("image_url"), "认不出 V1 基线里轮播图表的列定义");
		Map<String, Set<String>> commented = new TreeMap<>();
		EntityColumnAudit.parseMigration("-- ALTER TABLE t ADD COLUMN ghost int;\n/* ALTER TABLE t ADD COLUMN ghost2 int; */\n"
				+ "ALTER TABLE t3 ADD COLUMN IF NOT EXISTS added int;\n"
				+ "EXECUTE format('ALTER TABLE %I.t2 ADD COLUMN IF NOT EXISTS live varchar(8)', sch);", commented);
		assertTrue(!commented.containsKey("t") && commented.getOrDefault("t2", Set.of()).contains("live")
				&& commented.getOrDefault("t3", Set.of()).contains("added"),
				"注释里的 DDL 不能算建列；ALTER … ADD COLUMN 与 format('%I.表') 形式的动态 DDL 要认得：" + commented);
	}
}
