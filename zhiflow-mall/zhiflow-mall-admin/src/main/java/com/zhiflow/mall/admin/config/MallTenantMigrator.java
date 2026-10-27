/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.config;

import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.data.ddl.DdlExecutionRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 商城 schema（默认租户 {@code zhiflow_mall_1}）的结构迁移。
 * <p>
 * 应用启动完成后执行 classpath:db/migration/mall 下的版本化迁移，历史表 {schema}.flyway_schema_history_mall：
 * 空库首次启动执行 V1 基线（建表 + 默认店铺），以后的结构变更写 V2、V3……，商城 DDL 的唯一发布方式就是提交新版本。
 * 排在共享 schema 迁移（public / upms / wx）之后：表上的状态机守卫触发器引用 {@code public.fn_state_guard()}。
 * <p>
 * 某个版本因对象已存在而失败时走「已存在即标记」回填（SQLState 42P07/42701/42710 且消息含 already exists）。
 * 失败只记 ERROR 不阻断启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.tenant-migrator.enabled", havingValue = "true", matchIfMissing = true)
public class MallTenantMigrator {

    private static final String HISTORY_TABLE = "flyway_schema_history_mall";
    private static final String LOCATION = "classpath:db/migration/mall";
    /** mall schema 前缀；租户 schema = {@code SCHEMA_PREFIX + "_" + tenantId}，base schema 无后缀。 */
    private static final String SCHEMA_PREFIX = "zhiflow_mall";
    private static final Pattern SCRIPT_VERSION = Pattern.compile("Script V(\\d+)__");
    private static final int MAX_ROUNDS_PER_SCHEMA = 30;

    private final DataSource dataSource;

    /**
     * DDL 执行身份（压测后 CARD-01）。开关 {@code zhiflow.perf.db-ddl-set-role.enabled} 打开后，Flyway 与历史回填
     * 改走「物理连接 + SET ROLE gp_migrator」，应用账号本身不需要 DDL 权限；关时走不切身份的物理连接。
     */
    private DdlExecutionRole ddlExecutionRole;

    @Autowired(required = false)
    void setDdlExecutionRole(DdlExecutionRole ddlExecutionRole) {
        this.ddlExecutionRole = ddlExecutionRole;
    }

    /**
     * 迁移用的数据源：<b>不论 DDL 身份开关，都不借业务连接池</b>（10-18 租户隔离修复）。
     * <p>
     * Flyway 的 {@code initSql} 把 search_path 改成被迁移的 schema，关闭时只复原到 initSql 之后的值，
     * 借业务池的连接就会带着别的租户 schema 回池；{@code SchemaInterceptor} 记忆命中时不再 SET，
     * 下一条业务语句就在别的租户 schema 里读写。启动时 {@link #migrateAll} 逐 schema 连一遍（没有待执行版本也连），
     * 开租户时 {@link #migrateSchema} 再连一遍，两条路都走这里。
     * <p>
     * {@code ddlExecutionRole == null} 只出现在测试里手工 new 的实例（生产由 common-data 自动装配，必然有）。
     */
    DataSource ddlDataSource() {
        return ddlExecutionRole == null ? dataSource : ddlExecutionRole.migrationDataSource();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateAll() {
        // 单店铺单租户（S-36）：只迁默认租户的 schema，空库首次启动时由 Flyway 建出 schema 并执行 V1 基线
        Set<String> schemas = Set.of(SCHEMA_PREFIX + "_" + CommonConstants.DEFAULT_TENANT_ID);
        int ok = 0, fail = 0, applied = 0;
        List<String> backfills = new ArrayList<>();
        for (String schema : schemas) {
            SchemaResult r = migrateSchema(schema);
            ok += r.succeeded();
            fail += r.failed();
            applied += r.applied();
            backfills.addAll(r.backfills());
        }
        log.info("[Mall迁移] 完成: schema {} 个, 成功 {}, 失败 {}, 新应用 {} 个, 回填 {} 个",
                schemas.size(), ok, fail, applied, backfills.size());
    }

    /** 单个 schema 的迁移结果。succeeded / failed 按 Flyway 成功调用次数与失败次数计，与原先 migrateAll 的计数口径一致。 */
    public record SchemaResult(String schema, int succeeded, int failed, int applied, List<String> backfills,
                               String targetVersion) {

        public SchemaResult(String schema, int succeeded, int failed, int applied, List<String> backfills) {
            this(schema, succeeded, failed, applied, backfills, null);
        }

        public boolean ok() {
            return failed == 0 && succeeded > 0;
        }
    }

    /**
     * 迁移单个 schema（含「对象已存在即回填历史」的重试）。
     */
    public SchemaResult migrateSchema(String schema) {
        int ok = 0, fail = 0, applied = 0;
        List<String> backfills = new ArrayList<>();
        String targetVersion = null;
        boolean done = false;
        for (int round = 0; round < MAX_ROUNDS_PER_SCHEMA && !done; round++) {
            try {
                MigrateResult r = buildFlyway(schema).migrate();
                ok++;
                applied += r.migrationsExecuted;
                targetVersion = r.targetSchemaVersion;
                if (r.migrationsExecuted > 0) {
                    log.info("[Mall迁移] {} 应用 {} 个迁移 -> V{}", schema, r.migrationsExecuted, r.targetSchemaVersion);
                }
                done = true;
            } catch (Exception e) {
                String v = alreadyExistsVersion(e);
                if (v != null) {
                    try {
                        backfillHistory(schema, v);
                        backfills.add(schema + " V" + v);
                        log.warn("[Mall迁移] {} V{} 对象已存在，标记为已应用（克隆自模板或历史手工 DDL）", schema, v);
                    } catch (Exception be) {
                        log.error("[Mall迁移] {} V{} 历史回填失败（不阻断启动）", schema, v, be);
                        fail++;
                        done = true;
                    }
                } else {
                    log.error("[Mall迁移] {} 迁移失败（不阻断启动，需人工处理）", schema, e);
                    fail++;
                    done = true;
                }
            }
        }
        return new SchemaResult(schema, ok, fail, applied, backfills, targetVersion);
    }

    private Flyway buildFlyway(String schema) {
        return Flyway.configure()
                .dataSource(ddlDataSource())
                .schemas(schema)
                .locations(LOCATION)
                .table(HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .outOfOrder(true)
                .validateOnMigrate(false)
                .placeholderReplacement(false)
                .initSql("SET search_path TO " + schema + ", public")
                .load();
    }

    /** 迁移因目标对象已存在而失败时，返回版本号；其他失败返回 null */
    private String alreadyExistsVersion(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            sb.append(t.getMessage()).append(' ');
        }
        String msg = sb.toString();
        if (!msg.contains("already exists") && !msg.contains("已存在")) {
            return null;
        }
        Matcher m = SCRIPT_VERSION.matcher(msg);
        return m.find() ? m.group(1) : null;
    }

    private void backfillHistory(String schema, String version) throws Exception {
        try (Connection c = ddlDataSource().getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO " + schema + "." + HISTORY_TABLE
                    + " (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)"
                    + " SELECT COALESCE(MAX(installed_rank),0)+1, '" + version + "', 'auto backfill (already exists)', 'SQL',"
                    + " 'V" + version + "', NULL, 'mall-tenant-migrator', now(), 0, true"
                    + " FROM " + schema + "." + HISTORY_TABLE);
        }
    }
}
