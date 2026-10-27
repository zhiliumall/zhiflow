/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.upms.admin.config;

import com.zhiflow.common.data.ddl.DdlExecutionRole;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 共享 schema 的结构迁移：public（订单号机器位、状态机守卫）、upms（后台账号、角色、菜单、字典、省市区……）、
 * wx（公众号网页授权）。商城默认租户 schema 由 {@code MallTenantMigrator} 负责，排在本类之后。
 *
 * <p>每个 schema 一套 Flyway：脚本在 {@code classpath:db/migration/{public,upms,wx}}，历史表
 * {@code flyway_schema_history_{名}}。空库首次启动执行 V1 基线（建表 + 种子），以后的结构变更写 V2、V3……；
 * 已有表但没有历史表的老库按 V0 建基线后从 V1 起执行，所以脚本要能在已有结构上安全执行，或者干脆别给老库用。
 * 失败只记 ERROR、不阻断启动。
 *
 * <p>顺序固定为 public → upms → wx：商城表上的状态机守卫触发器引用 {@code public.fn_state_guard()}，
 * 首启口令初始化（{@link FirstBootAdminPasswordInitializer}）依赖 upms 的账号表。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "upms.schema-migrator.enabled", havingValue = "true", matchIfMissing = true)
public class SharedSchemaMigrator {

	static final int ORDER = 0;

	private static final Pattern SCHEMA_NAME = Pattern.compile("[a-z_][a-z0-9_]*");

	/** location 目录名、schema 名。 */
	private record Target(String name, String schema) {
	}

	private final DataSource dataSource;

	private final List<Target> targets;

	/** DDL 执行身份：迁移不借业务连接池（Flyway 会改连接的 search_path）。只有测试里手工 new 的实例为 null。 */
	private DdlExecutionRole ddlExecutionRole;

	public SharedSchemaMigrator(DataSource dataSource,
			@Value("${upms.schema-migrator.schema:zhiflow_upms}") String upmsSchema,
			@Value("${upms.schema-migrator.wx-schema:zhiflow_wx}") String wxSchema) {
		for (String s : List.of(upmsSchema, wxSchema)) {
			if (!SCHEMA_NAME.matcher(s).matches()) {
				throw new IllegalArgumentException("upms.schema-migrator 配的不是合法的 schema 名: " + s);
			}
		}
		this.dataSource = dataSource;
		this.targets = List.of(new Target("public", "public"), new Target("upms", upmsSchema), new Target("wx", wxSchema));
	}

	@Autowired(required = false)
	void setDdlExecutionRole(DdlExecutionRole ddlExecutionRole) {
		this.ddlExecutionRole = ddlExecutionRole;
	}

	@Order(ORDER)
	@EventListener(ApplicationReadyEvent.class)
	public void migrate() {
		for (Target t : targets) {
			try {
				MigrateResult r = Flyway.configure()
					.dataSource(ddlExecutionRole == null ? dataSource : ddlExecutionRole.migrationDataSource())
					.schemas(t.schema())
					.locations("classpath:db/migration/" + t.name())
					.table("flyway_schema_history_" + t.name())
					.baselineOnMigrate(true)
					.baselineVersion("0")
					.placeholderReplacement(false)
					.initSql("SET search_path TO " + t.schema() + ", public")
					.load()
					.migrate();
				if (r.migrationsExecuted > 0) {
					log.info("[共享 schema 迁移] {} 应用 {} 个迁移 -> V{}", t.schema(), r.migrationsExecuted, r.targetSchemaVersion);
				}
			}
			catch (RuntimeException e) {
				log.error("[共享 schema 迁移] {} 迁移失败（不阻断启动，需人工处理）", t.schema(), e);
			}
		}
	}
}
