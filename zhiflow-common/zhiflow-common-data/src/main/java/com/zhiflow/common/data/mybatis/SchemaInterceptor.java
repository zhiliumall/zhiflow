/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.data.mybatis;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.observability.TraceIds;
import com.zhiflow.common.data.shardingdatabase.config.ShardingDataBaseProperties;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.springframework.beans.factory.ObjectProvider;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.WeakHashMap;

/**
 * PostgreSQL Schema 拦截器
 * 在 SQL 执行前设置 search_path，避免在 SQL 中写 schema 前缀
 *
 * @author
 */
@Slf4j
public class SchemaInterceptor implements InnerInterceptor {

    /**
     * 合法租户号：可带负号的纯数字（-1 系统租户 / 1 / 雪花 ID）。
     * <p>
     * 用于阻断 search_path 拼接注入——tenantId 在多个公网免鉴权回调端点上是任意可填的
     * path variable，而下游会把它直接拼进 SET search_path 语句执行。
     */
    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("^-?\\d+$");

    private final ShardingDataBaseProperties shardingDataBaseProperties;

    /**
     * 按物理连接记忆最近一次**已提交**的 SET search_path 语句文本（autoCommit 与事务内都用它跳过重复 SET，A3）。
     * <p>
     * 键 = {@code connection.unwrap(Connection.class)} 剥出的物理连接：search_path 是
     * PG 会话状态，活在物理连接上。连接池是 Druid（2026-10-18 核实 /actuator/prometheus
     * 只有 druid_connections_*）：Druid 的 unwrap 无论配不配 stat 过滤器都返回底层 PgConnection；
     * HikariCP 同样返回 PgConnection。两种池下同一物理连接多次借还间实例稳定、不同物理连接互不相等、
     * 物理连接重建后是新键（SchemaInterceptorDruidTxMemoTest / SchemaInterceptorMemoTest 真 PG 钉死）。
     * WeakHashMap 弱引用键让物理连接被池淘汰后条目随 GC 消失，不泄漏。
     * <p>
     * 值 = 完整 SET 语句文本（非 tenantId）：三个分支（普通租户/系统租户兜底/跨模块 URL）
     * 生成的语句互不相同，按最终文本比对不会跨分支误命中。
     * <p>
     * <b>不变式（租户隔离的安全边界，改这里之前先读完）：记忆里只会有 autoCommit 下成功执行（即已提交）
     * 的 SET；自那次 SET 之后，本拦截器在这条物理连接上没有再发过 SET。</b>
     * <ul>
     *   <li>只在 autoCommit 分支、SET 成功之后 put；</li>
     *   <li>两个分支要 SET 时都<b>先</b> remove 再 SET（SET 抛异常——哪怕服务端其实已生效——也不会留下旧记忆）；</li>
     *   <li>事务内绝不 put——事务内的 SET（非 LOCAL）会被 ROLLBACK / ROLLBACK TO SAVEPOINT 撤销，
     *       也可能被 COMMIT 固化，记忆无法跟踪。</li>
     * </ul>
     * 由此：记忆存在 ⇒ PG 会话的已提交 search_path 等于记忆值，且当前事务（若有）里没有经本拦截器 SET 过；
     * 事务或 savepoint 回滚只能撤销事务内的 SET，最多撤回到已提交值 = 记忆值。所以无论 autoCommit 还是
     * 事务内，命中记忆时连接的实际 search_path 必等于记忆值，跳过 SET 不会串租户。
     * <p>
     * 不变式的前提（新增代码必须遵守）：没有别的代码绕过本拦截器在业务池连接上直接改 search_path
     * （裸 {@code SET search_path} / {@code set_config('search_path', ..)} / {@code RESET} / {@code DISCARD ALL}
     * / {@code Connection#setSchema}，以及事务内 {@code SET LOCAL search_path} 之后同一事务再跑 MyBatis 语句）。
     * 以后要加这类代码，必须走专用连接，或在连接退回池前恢复原值。
     * 现状（2026-10-23 全仓核实）：所有 Flyway 迁移——zhiflow-upms {@code SharedSchemaMigrator}、
     * zhiflow-mall-admin {@code MallTenantMigrator}（迁移与历史回填）——都走
     * {@code DdlExecutionRole.migrationDataSource()}，另开物理连接、不进业务池，initSql 改的 search_path
     * 随连接关闭消失；{@code DdlExecutionRole} 由 common-data 无条件自动装配，生产不存在回落到业务池的分支。
     * 全仓没有 {@code SET LOCAL search_path}；zhiflow-mall-api {@code CrossSchemaInterceptor} 的裸 SET
     * 是死代码（{@code SchemaContextHolder} 无人设置），启用前必须先按本条改造。
     * <p>
     * 部署约束：业务连接池不能经过 {@code pool_mode = transaction} 的 pgbouncer——那种模式下同一条池连接的
     * 相邻语句可能落到不同的后端会话，记忆不再对应真实的 search_path（autoCommit 复用本来就不成立，
     * 事务内复用也会跟着错）。直连 PG 或 session 模式没有这个问题。
     * <p>
     * 记忆在开关各种状态下都持续维护（两个分支都是 SET 前 remove、只有 autoCommit 成功 SET 后 put），
     * {@code memoize} 开关只控制"是否用记忆跳过"（autoCommit 与事务内一起），运行时切换开关不产生陈旧记忆窗口。
     */
    private final Map<Connection, String> searchPathMemo =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * search_path 处理结果计数（A3）：标签 tx = true/false（是否在事务内），result = set（发了 SET）/ hit（命中记忆跳过）。
     * 命中率 = hit / (hit + set)；{@code memoize} 关时只有 result=set。
     */
    static final String SEARCH_PATH_METRIC = "zhiflow.schema.search_path";

    /** 无租户 SQL 计数（第 6 步第 4 块）。标签 statement = MyBatis 语句 id，超过上限的新 id 记为 other。 */
    static final String NO_TENANT_METRIC = "zhiflow.schema.no_tenant";

    static final int MAX_NO_TENANT_STATEMENTS = 200;

    /** OTel span 属性：这条 SQL 落在哪个 schema（定时任务写错 schema 这类问题靠它当场看见）。 */
    static final String DB_SCHEMA_ATTRIBUTE = "db.schema";

    /** 可为 null（测试 / 没有 Micrometer 的装配）：只打日志不计数。 */
    private final ObjectProvider<MeterRegistry> meterRegistry;

    /** 已出现过的无租户语句 id：只在首次出现时打 WARN，稳态下没有日志噪声。 */
    private final Set<String> noTenantSeen = ConcurrentHashMap.newKeySet();

    /**
     * search_path 计数器，按 {@link #counterIndex} 排列；首次拿到 MeterRegistry 时建好缓存，
     * 之后每条 SQL 只做一次数组下标 + increment，不再按名字查表。
     */
    private volatile Counter[] searchPathCounters;

    public SchemaInterceptor(ShardingDataBaseProperties shardingDataBaseProperties) {
        this(shardingDataBaseProperties, null);
    }

    public SchemaInterceptor(ShardingDataBaseProperties shardingDataBaseProperties,
                             ObjectProvider<MeterRegistry> meterRegistry) {
        this.shardingDataBaseProperties = shardingDataBaseProperties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        // 无租户上下文：与原来一样不设 search_path、直接放行（resolveSearchPathSql 对空租户号同样返回 null）；
        // 只在 no-tenant-mode=log 时额外记一笔。
        if (StrUtil.isBlank(TenantContextHolder.getTenantId())) {
            recordNoTenant(sh, connection);
            return;
        }
        try {
            String setSearchPathSql = resolveSearchPathSql(connection);
            if (setSearchPathSql == null) {
                return;
            }
            tagSchema(setSearchPathSql);

            Connection physical = unwrapPhysical(connection);
            if (!connection.getAutoCommit()) {
                // 事务中：记忆值 = 这条物理连接已提交的 search_path，且本事务里还没经本拦截器 SET 过
                // （见 searchPathMemo 的不变式）。事务 / savepoint 回滚最多撤回到已提交值，
                // 所以记忆与目标相同时连接上的实际值必然就是目标值，可以跳过（A3，与 autoCommit 共用 memoize 开关）。
                // 先比记忆再读开关：开关在 @RefreshScope 代理上，每读一次都要过代理，记忆缺失时省掉。
                if (setSearchPathSql.equals(searchPathMemo.get(physical)) && isMemoizeEnabled()) {
                    countSearchPath(true, true);
                    return;
                }
                // 需要 SET：PG 的 SET（非 LOCAL）会被 ROLLBACK / ROLLBACK TO SAVEPOINT 回退、也会被 COMMIT 固化，
                // 事务窗口内无法跟踪——必须先删记忆再 SET（顺序不能反：SET 失败也不能留下记忆），
                // 本事务剩余语句因记忆缺失全部照旧 SET；事务结束后首条 autoCommit 语句必然 miss、重设并写回记忆。
                searchPathMemo.remove(physical);
                applySearchPath(connection, setSearchPathSql);
                countSearchPath(true, false);
                return;
            }

            // autoCommit：SET 各自成事务、立即提交，不存在可回滚的外围事务，
            // 记忆值恒等于 PG 实际会话状态，命中即可安全跳过这次 DB 往返。
            if (setSearchPathSql.equals(searchPathMemo.get(physical)) && isMemoizeEnabled()) {
                countSearchPath(false, true);
                return;
            }
            // 与事务分支同样先删再 SET：SET 可能在服务端已生效、客户端才抛异常（如语句发出后读响应失败），
            // 留着旧记忆就违反不变式「自那次 SET 之后本拦截器没再发过 SET」，下一条同旧值的语句
            // （autoCommit 或事务内）会误命中、跑在这次 SET 的 schema 上。
            searchPathMemo.remove(physical);
            applySearchPath(connection, setSearchPathSql);
            // 只有 SET 成功（已提交）才写记忆——不变式的唯一写入点
            searchPathMemo.put(physical, setSearchPathSql);
            countSearchPath(false, false);
        } catch (SQLException e) {
            log.error("Failed to set PostgreSQL search_path", e);
            // 不抛出异常，避免影响正常查询
        }
    }

    /**
     * 记录一条「租户分库连接上没有租户号」的 SQL（第 6 步第 4 块，先记录、清零后再改报错）。
     * <p>
     * 只在 {@code no-tenant-mode} 为 log / reject 时生效；off（默认）直接返回，与历史行为一致。
     * 不在连接上执行任何语句（URL 取自 JDBC 元数据对象，不走数据库）；自身任何异常都吞掉，绝不影响业务 SQL。
     * upms / wx 这类 URL 指向别的模块 schema 的连接本来就没有租户分库，不算。
     */
    private void recordNoTenant(StatementHandler sh, Connection connection) {
        if (!isNoTenantRecordEnabled()) {
            return;
        }
        try {
            String dbName = resolveDbName();
            if (StrUtil.isBlank(dbName)) {
                return;
            }
            String statement = statementId(sh);
            boolean first = false;
            if (!noTenantSeen.contains(statement)) {
                if (noTenantSeen.size() >= MAX_NO_TENANT_STATEMENTS) {
                    statement = "other";
                }
                first = noTenantSeen.add(statement);
            }
            MeterRegistry registry = meterRegistry == null ? null : meterRegistry.getIfAvailable();
            if (registry != null) {
                registry.counter(NO_TENANT_METRIC, "statement", statement).increment();
            }
            if (first) {
                log.warn("[无租户SQL] 首次出现 statement={} schema前缀={} thread={} 调用方={}",
                        statement, dbName, Thread.currentThread().getName(), callers());
            }
        } catch (Throwable t) {
            log.debug("无租户 SQL 记录失败（不影响业务）", t);
        }
    }

    private boolean isNoTenantRecordEnabled() {
        if (shardingDataBaseProperties == null) {
            return false;
        }
        String mode = shardingDataBaseProperties.getNoTenantMode();
        return "log".equalsIgnoreCase(mode) || "reject".equalsIgnoreCase(mode);
    }

    private static String statementId(StatementHandler sh) {
        if (sh == null) {
            return "unknown";
        }
        try {
            return PluginUtils.mpStatementHandler(sh).mappedStatement().getId();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /** 调用栈里离这条 SQL 最近的 8 个业务帧（跳过本包的拦截器），用来定位是哪条代码路径没设租户。 */
    private static String callers() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(f -> f.getClassName().startsWith("com.zhiflow.")
                        && !f.getClassName().startsWith("com.zhiflow.common.data.mybatis."))
                .limit(8)
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1)
                        + "#" + f.getMethodName() + ":" + f.getLineNumber())
                .collect(Collectors.joining(" <- ")));
    }

    /** schema 前缀（{@code spring.datasource.sharding.dbname}）。开源分支只有一个数据源，不再按数据源区分。 */
    private String resolveDbName() {
        return shardingDataBaseProperties.getDbname();
    }

    /**
     * 计算当前语句应执行的 SET search_path 语句文本；返回 null 表示放行不设置。
     * 合并原先三个分支，语句文本与历史逐字节一致（含普通租户分支的 "SET  " 双空格）。
     */
    private String resolveSearchPathSql(Connection connection) throws SQLException {
        // 1. 从上下文获取租户 ID
        String tenantId = TenantContextHolder.getTenantId();

        // 2. 无租户上下文，不设置 schema
        if (StrUtil.isBlank(tenantId)) {
            return null;
        }

        // 2b. 租户号格式校验 —— 安全闸，不可删。
        //
        // tenantId 会被**直接拼接**进下面的 "SET search_path TO {dbName}_{tenantId}, public"
        // 并由 Statement.execute 执行（见 applySearchPath），是一条实打实的 SQL 注入路径。
        // 而多个**公网免鉴权**回调端点（PublicUrlConstants 白名单里的
        // /orderinfo/notify-order-wechat/{tenantId} 等）的 tenantId 是任意可填的 path variable，
        // 直接进 TenantContextHolder.setTenantId 而无任何校验。
        //
        // 租户号全为纯数字（-1 / 1 / 雪花 ID），故只放行 ^-?\d+$；
        // 非法值一律不设 search_path（返回 null = 沿用连接默认 schema），并留痕告警。
        if (!TENANT_ID_PATTERN.matcher(tenantId).matches()) {
            log.error("非法租户号，拒绝设置 search_path（疑似注入尝试）: {}",
                    StrUtil.maxLength(tenantId, 64));
            return null;
        }

        String dbName = resolveDbName();

        if (StrUtil.isBlank(dbName)) {
            return null;
        }

        // 3. 系统租户（-1）：base schema（不带后缀）数据状态不明，不能作为超管的读写目标，
        //    兜到默认租户的 schema（默认 zhiflow_mall_1）。
        String effectiveTenant = tenantId;
        if (StrUtil.equals(tenantId, CommonConstants.SYSTEM_TENANT_ID)) {
            effectiveTenant = StrUtil.blankToDefault(shardingDataBaseProperties.getDefaultTenantId(),
                    CommonConstants.DEFAULT_TENANT_ID);
        }

        // 4. search_path = 租户 schema, 共享 schema（upms / wx，单数据源后与商城共用一个连接）, public。
        //    租户 schema 在最前，优先匹配租户表；public 兜底用于 pg 扩展（pgvector 等）。
        //    ⚠️ 共享 schema 里不能有与租户 schema 同名的表，否则按顺序会命中租户 schema 那张（见 V183）。
        StringBuilder sql = new StringBuilder("SET search_path TO ").append(dbName).append('_').append(effectiveTenant);
        for (String shared : shardingDataBaseProperties.getSharedSchemas()) {
            sql.append(", ").append(shared);
        }
        return sql.append(", public").toString();
    }

    /**
     * 把 schema 挂到 OTel 当前 span（记忆命中、跳过 SET 时也挂——落在哪个 schema 与发没发 SET 无关）。
     * 没挂 agent 时第一行就返回，不做任何字符串处理；本方法不影响 search_path 本身。
     */
    static void tagSchema(String setSearchPathSql) {
        if (!TraceIds.otelSpanValid()) {
            return;
        }
        String schema = schemaOf(setSearchPathSql);
        if (schema != null) {
            TraceIds.setSpanAttribute(DB_SCHEMA_ATTRIBUTE, schema);
        }
    }

    /** {@code SET  search_path TO a, public} → {@code a}；认不出返回 null。 */
    static String schemaOf(String setSearchPathSql) {
        if (setSearchPathSql == null) {
            return null;
        }
        int to = setSearchPathSql.indexOf(" TO ");
        if (to < 0) {
            return null;
        }
        String rest = setSearchPathSql.substring(to + 4).trim();
        int comma = rest.indexOf(',');
        String schema = (comma < 0 ? rest : rest.substring(0, comma)).trim();
        return schema.isEmpty() ? null : schema;
    }

    private void applySearchPath(Connection connection, String setSearchPathSql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(setSearchPathSql);
            if (log.isDebugEnabled()) {
                log.debug("Set PostgreSQL search_path: {}", setSearchPathSql);
            }
        }
    }

    /**
     * 剥到物理连接作为记忆键。unwrap 失败或驱动返回 null 时退化为以池代理对象为键——
     * 代理每次借出都是新实例，记忆永不跨借用命中 = 每条都 SET，安全无泄漏（只损失优化）。
     */
    private Connection unwrapPhysical(Connection connection) {
        try {
            Connection physical = connection.unwrap(Connection.class);
            return physical != null ? physical : connection;
        } catch (SQLException | RuntimeException e) {
            return connection;
        }
    }

    private boolean isMemoizeEnabled() {
        // ShardingDataBaseProperties 是 @RefreshScope，配置改完即时生效
        return shardingDataBaseProperties != null
                && Boolean.TRUE.equals(shardingDataBaseProperties.getSchemaInterceptorMemoize());
    }

    private static int counterIndex(boolean inTx, boolean hit) {
        return (inTx ? 2 : 0) + (hit ? 1 : 0);
    }

    /** search_path 计数；没有 MeterRegistry 时什么都不做，自身异常吞掉，绝不影响业务 SQL。 */
    private void countSearchPath(boolean inTx, boolean hit) {
        try {
            Counter[] counters = searchPathCounters;
            if (counters == null) {
                MeterRegistry registry = meterRegistry == null ? null : meterRegistry.getIfAvailable();
                if (registry == null) {
                    return;
                }
                counters = new Counter[4];
                for (boolean tx : new boolean[]{false, true}) {
                    for (boolean h : new boolean[]{false, true}) {
                        counters[counterIndex(tx, h)] = registry.counter(SEARCH_PATH_METRIC,
                                "tx", String.valueOf(tx), "result", h ? "hit" : "set");
                    }
                }
                searchPathCounters = counters;
            }
            counters[counterIndex(inTx, hit)].increment();
        } catch (Throwable t) {
            log.debug("search_path 计数失败（不影响业务）", t);
        }
    }


    /**
     * 解析查询字符串为 Map
     *
     * @param query 查询字符串 (e.g., "param1=value1&param2=value2")
     * @return 参数 Map
     */
    private Map<String, String> parseQueryString(String query) {
        Map<String, String> params = new HashMap<>();

        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                String key = pair.substring(0, idx);
                String value = pair.substring(idx + 1);
                try {
                    // URL decode
                    value = URLDecoder.decode(value, StandardCharsets.UTF_8);
                } catch (Exception _) {
                    // 忽略解码错误，使用原值
                }
                params.put(key, value);
            }
        }

        return params;
    }

    @Override
    public void setProperties(Properties properties) {
        InnerInterceptor.super.setProperties(properties);
    }
}
