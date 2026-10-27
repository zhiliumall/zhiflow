package com.zhiflow.common.data.mybatis;

import com.zhiflow.common.data.shardingdatabase.config.ShardingDataBaseProperties;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SchemaInterceptor 按连接记忆——对抗性测试矩阵（#1–#9）+ 影子对比；
 * #11/#12 是事务内复用记忆（A3，10-23 起只受 memoize 一个开关控制）在 HikariCP 下的场景。
 * <p>
 * 需要真 PG（多 schema），通过系统属性注入连接信息，未配置时整类跳过（不入 CI 常规路径）：
 * <pre>
 * ./mvnw test -pl zhiflow-common/zhiflow-common-data -Dtest=SchemaInterceptorMemoTest \
 *   -Db10.pg.url='jdbc:postgresql://HOST:5432/zhiflow?ssl=false' \
 *   -Db10.pg.user=postgres -Db10.pg.password=***
 * </pre>
 * 对库只做：SET search_path / SELECT current_setting / BEGIN-ROLLBACK。无任何 DDL/DML。
 * <p>
 * 高并发矩阵项（#7）迭代次数可用 -Db10.soak.iterations=N 放大做长跑压测（默认 30）。
 */
class SchemaInterceptorMemoTest {

    private static final String PG_URL = System.getProperty("b10.pg.url", "");
    private static final String PG_USER = System.getProperty("b10.pg.user", "postgres");
    private static final String PG_PASSWORD = System.getProperty("b10.pg.password", "");
    /** #7 每线程迭代数。默认按约 220ms 的数据库往返时延校准（50线程×6≈1分钟墙钟）；长跑压测可调大。 */
    private static final int SOAK_ITERATIONS = Integer.getInteger("b10.soak.iterations", 6);

    private static final String DBNAME = "zhiflow_mall";
    private static final String TENANT_A = "1";
    private static final String TENANT_B = "1000000000000000003";
    /**
     * SET 语句文本。开源分支单数据源后只剩一种形态：系统租户（-1）兜到默认租户，
     * 与租户 1 生成同一条语句——两者落同一个 schema，记忆命中是对的。
     */
    private static final String GOLDEN_NORMAL_A = "SET search_path TO zhiflow_mall_1, public";
    private static final String GOLDEN_NORMAL_B = "SET search_path TO zhiflow_mall_" + TENANT_B + ", public";
    private static final String GOLDEN_SYSTEM = GOLDEN_NORMAL_A;

    @BeforeAll
    static void requiresRealPg() {
        Assumptions.assumeTrue(!PG_URL.isBlank() && !PG_PASSWORD.isBlank(),
                "未配置 -Db10.pg.url/-Db10.pg.password，跳过真 PG 对抗矩阵");
        try (HikariDataSource ds = pool(PG_URL, 1); Connection c = ds.getConnection()) {
            Assumptions.assumeTrue(c.isValid(5), "PG 连接无效");
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "PG 不可达: " + e.getMessage());
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    // ---------------------------------------------------------------- 前置

    @Test
    @DisplayName("前置：unwrap(Connection.class) 同物理连接借还间稳定、不同物理连接不相等")
    void matrix0_unwrapPrerequisite() throws Exception {
        try (HikariDataSource ds = pool(PG_URL, 1)) {
            Connection p1 = ds.getConnection();
            Connection u1 = p1.unwrap(Connection.class);
            assertSame(u1, p1.unwrap(Connection.class), "同一借用内 unwrap 应稳定");
            assertNotSame(p1, u1, "unwrap 应剥出物理连接而非返回池代理自身");
            p1.close();
            try (Connection p2 = ds.getConnection()) {
                assertSame(u1, p2.unwrap(Connection.class), "同一物理连接跨借还 unwrap 应返回同一实例");
                assertNotSame(p1, p2, "池代理每次借出应是新实例");
            }
        }
        try (HikariDataSource ds = pool(PG_URL, 2);
             Connection a = ds.getConnection();
             Connection b = ds.getConnection()) {
            assertNotSame(a.unwrap(Connection.class), b.unwrap(Connection.class),
                    "不同物理连接 unwrap 必须不相等");
        }
    }

    // ---------------------------------------------------------------- 矩阵 1-9

    @Test
    @DisplayName("#1 单连接 autoCommit 同租户连发 N 条：只 SET 一次，search_path 恒正确")
    void matrix1_sameTenantRepeated_singleSet() throws Exception {
        SchemaInterceptor si = interceptor(true);
        List<String> rec = recorder();
        try (HikariDataSource ds = pool(PG_URL, 1); Connection c = ds.getConnection()) {
            TenantContextHolder.setTenantId(TENANT_A);
            for (int i = 0; i < 5; i++) {
                assertTrue(probe(si, c, rec).contains("zhiflow_mall_1"));
            }
        }
        assertEquals(List.of(GOLDEN_NORMAL_A), rec, "5 条语句应只发 1 次 SET");
    }

    @Test
    @DisplayName("#2 池=1 交错 A→B→A：每次租户切换都重设，互不读到对方 schema")
    void matrix2_tenantSwitchInterleaved_poolSize1() throws Exception {
        SchemaInterceptor si = interceptor(true);
        List<String> rec = recorder();
        try (HikariDataSource ds = pool(PG_URL, 1)) {
            for (String tenant : List.of(TENANT_A, TENANT_B, TENANT_A)) {
                try (Connection c = ds.getConnection()) {
                    TenantContextHolder.setTenantId(tenant);
                    String path = probe(si, c, rec);
                    assertTrue(path.contains("zhiflow_mall_" + tenant),
                            "租户 " + tenant + " 读到了 " + path);
                }
            }
        }
        assertEquals(List.of(GOLDEN_NORMAL_A, GOLDEN_NORMAL_B, GOLDEN_NORMAL_A), rec,
                "每次切换租户都必须重设");
    }

    @Test
    @DisplayName("#3 事务 ROLLBACK 回退 SET 后：首条 autoCommit 必重设（memo 已清），不残留旧租户")
    void matrix3_rollbackRevertsSet_memoCleared() throws Exception {
        SchemaInterceptor si = interceptor(true);
        List<String> rec = recorder();
        try (HikariDataSource ds = pool(PG_URL, 1)) {
            // step1: autoCommit 下租户 A，memo=A，PG 实际=A
            try (Connection c = ds.getConnection()) {
                TenantContextHolder.setTenantId(TENANT_A);
                assertTrue(probe(si, c, rec).contains("zhiflow_mall_1"));
            }
            // step2: 同一物理连接开事务，租户 B 的 SET 在事务内执行，随 ROLLBACK 被 PG 回退到 A
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                TenantContextHolder.setTenantId(TENANT_B);
                String inTx = probe(si, c, rec);
                assertTrue(inTx.contains("zhiflow_mall_" + TENANT_B), "事务内应看到 B: " + inTx);
                c.rollback();
                c.setAutoCommit(true);
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT current_setting('search_path')")) {
                    rs.next();
                    assertTrue(rs.getString(1).contains("zhiflow_mall_1"),
                            "ROLLBACK 应把 search_path 回退到 A（PG 语义前提）");
                }
            }
            // step3: 朴素 memo 会以为"该连接=B"而跳过 → 读到 A 的数据；本实现必须重设
            try (Connection c = ds.getConnection()) {
                TenantContextHolder.setTenantId(TENANT_B);
                int before = rec.size();
                String path = probe(si, c, rec);
                assertEquals(before + 1, rec.size(), "回滚后首条 autoCommit 语句必须重新 SET");
                assertTrue(path.contains("zhiflow_mall_" + TENANT_B),
                        "跨租户泄漏：期望 B 实际 " + path);
            }
        }
    }

    @Test
    @DisplayName("#4 事务内 savepoint 回退（连接没有已提交记忆）：事务内每条都 SET，结果恒正确")
    void matrix4_savepointRollback_txAlwaysSets() throws Exception {
        SchemaInterceptor si = interceptor(true);
        List<String> rec = recorder();
        try (HikariDataSource ds = pool(PG_URL, 1); Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            TenantContextHolder.setTenantId(TENANT_A);
            assertTrue(probe(si, c, rec).contains("zhiflow_mall_1"));
            var sp = c.setSavepoint("b10");
            TenantContextHolder.setTenantId(TENANT_B);
            assertTrue(probe(si, c, rec).contains("zhiflow_mall_" + TENANT_B));
            c.rollback(sp);
            // savepoint 回退掉了 B 的 SET，PG 实际=A；事务内从不写记忆（这条连接也没有已提交记忆），下一条仍 SET
            assertTrue(probe(si, c, rec).contains("zhiflow_mall_" + TENANT_B));
            c.rollback();
            c.setAutoCommit(true);
        }
        assertEquals(3, rec.size(), "没有已提交记忆时事务内每条语句都必须 SET，绝不跳过");
    }

    @Test
    @DisplayName("#11 memoize=true 事务内复用已提交记忆：命中跳过；savepoint / 整体回滚、提交固化后都不串租户")
    void matrix11_memoizeOn_txReusesCommittedMemo() throws Exception {
        SchemaInterceptor si = interceptor(true);
        List<String> rec = recorder();
        try (HikariDataSource ds = pool(PG_URL, 1)) {
            // autoCommit A：SET 并写记忆 = 已提交值 A
            try (Connection c = ds.getConnection()) {
                TenantContextHolder.setTenantId(TENANT_A);
                assertTrue(probe(si, c, rec).contains("zhiflow_mall_1"));
            }
            // 事务：A 命中跳过；savepoint 后 B 必 SET（删记忆）；回滚到 savepoint 后 A 必重设
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                TenantContextHolder.setTenantId(TENANT_A);
                int before = rec.size();
                assertTrue(probe(si, c, rec).contains("zhiflow_mall_1"));
                assertEquals(before, rec.size(), "事务内与已提交记忆相同：应跳过 SET");
                var sp = c.setSavepoint("a3");
                TenantContextHolder.setTenantId(TENANT_B);
                assertTrue(probe(si, c, rec).contains("zhiflow_mall_" + TENANT_B));
                c.rollback(sp);
                TenantContextHolder.setTenantId(TENANT_A);
                assertTrue(probe(si, c, rec).contains("zhiflow_mall_1"));
                c.rollback();
                c.setAutoCommit(true);
            }
            // 事务内 SET B 后提交（B 被固化为已提交值）：之后 A 不能命中旧记忆
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                TenantContextHolder.setTenantId(TENANT_B);
                assertTrue(probe(si, c, rec).contains("zhiflow_mall_" + TENANT_B));
                c.commit();
                c.setAutoCommit(true);
                TenantContextHolder.setTenantId(TENANT_A);
                String path = probe(si, c, rec);
                assertTrue(path.contains("zhiflow_mall_1"), "提交固化 B 后 A 必须重设，实际 " + path);
            }
            // 记忆已写回 A：新事务连续两条都命中
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                TenantContextHolder.setTenantId(TENANT_A);
                int before = rec.size();
                assertTrue(probe(si, c, rec).contains("zhiflow_mall_1"));
                assertTrue(probe(si, c, rec).contains("zhiflow_mall_1"));
                assertEquals(before, rec.size(), "已提交记忆 = A：事务内同租户应全部跳过");
                c.rollback();
                c.setAutoCommit(true);
            }
        }
        assertEquals(List.of(GOLDEN_NORMAL_A, GOLDEN_NORMAL_B, GOLDEN_NORMAL_A, GOLDEN_NORMAL_B, GOLDEN_NORMAL_A),
                rec, "只有目标与已提交记忆不同、或记忆已被事务内 SET 删掉时才 SET");
    }

    @Test
    @DisplayName("#12 memoize=false：autoCommit 与事务内都逐条 SET")
    void matrix12_memoizeOff_txSetsEveryStatement() throws Exception {
        SchemaInterceptor si = interceptor(false);
        List<String> rec = recorder();
        try (HikariDataSource ds = pool(PG_URL, 1); Connection c = ds.getConnection()) {
            TenantContextHolder.setTenantId(TENANT_A);
            probe(si, c, rec);
            c.setAutoCommit(false);
            probe(si, c, rec);
            probe(si, c, rec);
            c.rollback();
            c.setAutoCommit(true);
        }
        assertEquals(List.of(GOLDEN_NORMAL_A, GOLDEN_NORMAL_A, GOLDEN_NORMAL_A), rec, "关开关 = 事务内也逐条 SET");
    }

    @Test
    @DisplayName("#5 两个连接池（不同物理连接）：memo 按物理连接隔离，互不误命中")
    void matrix5_twoPools_memoIsolatedByPhysicalConnection() throws Exception {
        SchemaInterceptor si = interceptor(true);
        List<String> rec = recorder();
        try (HikariDataSource first = pool(PG_URL, 1); HikariDataSource second = pool(PG_URL, 1)) {
            TenantContextHolder.setTenantId(TENANT_A);
            for (int round = 0; round < 2; round++) {
                try (Connection c = first.getConnection()) {
                    assertTrue(probe(si, c, rec).contains("zhiflow_mall_1"));
                }
                try (Connection c = second.getConnection()) {
                    assertTrue(probe(si, c, rec).contains("zhiflow_mall_1"));
                }
            }
        }
        // 两个物理连接各 SET 一次后各自命中自己的 memo，交错访问不互相冲掉
        assertEquals(List.of(GOLDEN_NORMAL_A, GOLDEN_NORMAL_A), rec, "memo 按物理连接隔离：两池各只 SET 一次");
    }

    @Test
    @DisplayName("#10 共享 schema：排在租户 schema 之后、public 之前，系统租户同样带上")
    void matrix10_sharedSchemasFollowTenantSchema() throws Exception {
        ShardingDataBaseProperties props = props(true);
        props.setSharedSchemas(List.of("zhiflow_upms", "zhiflow_wx"));
        SchemaInterceptor si = new SchemaInterceptor(props);
        List<String> rec = recorder();
        try (HikariDataSource ds = pool(PG_URL, 1); Connection c = ds.getConnection()) {
            TenantContextHolder.setTenantId(TENANT_A);
            assertEquals("zhiflow_mall_1, zhiflow_upms, zhiflow_wx, public", probe(si, c, rec));
            TenantContextHolder.setTenantId("-1");
            assertEquals("zhiflow_mall_1, zhiflow_upms, zhiflow_wx, public", probe(si, c, rec));
        }
        assertEquals(List.of("SET search_path TO zhiflow_mall_1, zhiflow_upms, zhiflow_wx, public"), rec,
                "-1 与租户 1 同一条语句，第二条命中记忆");
    }

    @Test
    @DisplayName("#6 系统租户(-1) 与普通租户交错：-1 与租户 1 同一条语句可命中，换到别的租户必重设")
    void matrix6_systemTenantVsNormalTenant_noFalseHit() throws Exception {
        SchemaInterceptor si = interceptor(true);
        List<String> rec = recorder();
        try (HikariDataSource ds = pool(PG_URL, 1); Connection c = ds.getConnection()) {
            TenantContextHolder.setTenantId("-1");
            assertTrue(probe(si, c, rec).contains("zhiflow_mall_1"));
            // 与 -1 同指向 mall_1、同一条语句：命中记忆跳过是对的
            TenantContextHolder.setTenantId(TENANT_A);
            assertTrue(probe(si, c, rec).contains("zhiflow_mall_1"));
            TenantContextHolder.setTenantId(TENANT_B);
            assertTrue(probe(si, c, rec).contains("zhiflow_mall_" + TENANT_B));
            TenantContextHolder.setTenantId("-1");
            assertTrue(probe(si, c, rec).contains("zhiflow_mall_1"));
            TenantContextHolder.setTenantId("-1");
            assertTrue(probe(si, c, rec).contains("zhiflow_mall_1"));
        }
        assertEquals(List.of(GOLDEN_SYSTEM, GOLDEN_NORMAL_B, GOLDEN_SYSTEM), rec,
                "-1 与租户 1 同语句命中；换到别的租户必重设；连续 -1 第二条命中记忆跳过");
    }

    @Test
    @DisplayName("#7 高并发小池双租户混压（含事务操作）：零串号")
    void matrix7_highConcurrency_zeroCrossTenant() throws Exception {
        SchemaInterceptor si = interceptor(true);
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        try (HikariDataSource ds = pool(PG_URL, 3)) {
            int threads = 50;
            ExecutorService es = Executors.newFixedThreadPool(threads);
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                String tenant = (t % 2 == 0) ? TENANT_A : TENANT_B;
                String expected = "zhiflow_mall_" + tenant;
                es.submit(() -> {
                    try {
                        TenantContextHolder.setTenantId(tenant);
                        for (int i = 0; i < SOAK_ITERATIONS; i++) {
                            try (Connection c = ds.getConnection()) {
                                boolean inTx = (i % 10 == 9);
                                if (inTx) {
                                    c.setAutoCommit(false);
                                }
                                String path = probe(si, c, recorder());
                                if (!path.contains(expected)) {
                                    failures.add("tenant=" + tenant + " got=" + path + " inTx=" + inTx);
                                }
                                if (inTx) {
                                    c.rollback();
                                    c.setAutoCommit(true);
                                }
                            }
                        }
                    } catch (Exception e) {
                        failures.add("tenant=" + tenant + " ex=" + e);
                    } finally {
                        TenantContextHolder.clear();
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(600, TimeUnit.SECONDS), "并发压测超时");
            es.shutdownNow();
        }
        assertTrue(failures.isEmpty(), "跨租户串号/异常: " + failures);
    }

    @Test
    @DisplayName("#8 kill-switch 关（memoize=false）：每条都 SET")
    void matrix8_killSwitchOff_setsEveryStatement() throws Exception {
        SchemaInterceptor si = interceptor(false);
        List<String> rec = recorder();
        try (HikariDataSource ds = pool(PG_URL, 1); Connection c = ds.getConnection()) {
            TenantContextHolder.setTenantId(TENANT_A);
            probe(si, c, rec);
            probe(si, c, rec);
            TenantContextHolder.setTenantId("-1");
            probe(si, c, rec);
            probe(si, c, rec);
        }
        assertEquals(List.of(GOLDEN_NORMAL_A, GOLDEN_NORMAL_A, GOLDEN_SYSTEM, GOLDEN_SYSTEM), rec,
                "关开关 = 逐条 SET");
    }

    @Test
    @DisplayName("#9 tenantId 为空的放行分支：不 SET、不写记忆、不影响前后语句")
    void matrix9_blankTenantPassthrough() throws Exception {
        SchemaInterceptor si = interceptor(true);
        List<String> rec = recorder();
        try (HikariDataSource ds = pool(PG_URL, 1); Connection c = ds.getConnection()) {
            TenantContextHolder.setTenantId(TENANT_A);
            assertTrue(probe(si, c, rec).contains("zhiflow_mall_1"));
            TenantContextHolder.clear();
            String duringBlank = probe(si, c, rec);
            assertTrue(duringBlank.contains("zhiflow_mall_1"), "放行分支不改连接状态");
            TenantContextHolder.setTenantId(TENANT_A);
            assertTrue(probe(si, c, rec).contains("zhiflow_mall_1"));
            TenantContextHolder.setTenantId(TENANT_B);
            assertTrue(probe(si, c, rec).contains("zhiflow_mall_" + TENANT_B));
        }
        assertEquals(List.of(GOLDEN_NORMAL_A, GOLDEN_NORMAL_B), rec,
                "空租户不 SET；其后同租户命中记忆跳过；换租户正常重设");
    }

    // ---------------------------------------------------------------- 影子对比

    @Test
    @DisplayName("影子对比：同一操作序列 memoize=false/true 两轮，逐操作结果与最终 search_path 完全一致")
    void shadowComparison_memoOnOff_identicalResults() throws Exception {
        List<String> off = runShadowSequence(false);
        List<String> on = runShadowSequence(true);
        assertEquals(off, on, "memoize 开关不得改变任何一次操作观察到的 search_path");
    }

    private List<String> runShadowSequence(boolean memoize) throws Exception {
        SchemaInterceptor si = interceptor(memoize);
        List<String> observed = new ArrayList<>();
        try (HikariDataSource ds = pool(PG_URL, 1)) {
            try (Connection c = ds.getConnection()) {
                TenantContextHolder.setTenantId(TENANT_A);
                observed.add(probe(si, c, recorder()));
                observed.add(probe(si, c, recorder()));
                TenantContextHolder.setTenantId(TENANT_B);
                observed.add(probe(si, c, recorder()));
                TenantContextHolder.clear();
                observed.add(probe(si, c, recorder()));
                TenantContextHolder.setTenantId("-1");
                observed.add(probe(si, c, recorder()));
            }
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                TenantContextHolder.setTenantId(TENANT_B);
                observed.add(probe(si, c, recorder()));
                c.rollback();
                c.setAutoCommit(true);
            }
            try (Connection c = ds.getConnection()) {
                TenantContextHolder.setTenantId(TENANT_B);
                observed.add(probe(si, c, recorder()));
                TenantContextHolder.setTenantId(TENANT_A);
                observed.add(probe(si, c, recorder()));
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT current_setting('search_path')")) {
                    rs.next();
                    observed.add("final=" + rs.getString(1));
                }
            }
        } finally {
            TenantContextHolder.clear();
        }
        return observed;
    }

    // ---------------------------------------------------------------- 工具

    private static HikariDataSource pool(String url, int size) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(PG_USER);
        cfg.setPassword(PG_PASSWORD);
        cfg.setMaximumPoolSize(size);
        cfg.setMinimumIdle(size);
        // 高 RTT + 50 线程抢 3 连接的小池：借用等待要给足（#7 大迭代数长跑时排队可达数分钟），
        // 否则报的是池饥饿假失败而非串号
        cfg.setConnectionTimeout(600000);
        return new HikariDataSource(cfg);
    }

    private static ShardingDataBaseProperties props(boolean memoize) {
        ShardingDataBaseProperties p = new ShardingDataBaseProperties();
        p.setDbname(DBNAME);
        p.setDefaultTenantId("1");
        p.setSchemaInterceptorMemoize(memoize);
        return p;
    }

    private static SchemaInterceptor interceptor(boolean memoize) {
        return new SchemaInterceptor(props(memoize));
    }

    private static List<String> recorder() {
        return Collections.synchronizedList(new ArrayList<>());
    }

    /**
     * 跑一次"拦截器 + 探针"：beforePrepare 走监听 SET 的代理连接（记录发出的 SET 文本），
     * 然后在真连接上读 current_setting('search_path') 作为租户隔离断言依据。
     */
    private static String probe(SchemaInterceptor si, Connection real, List<String> rec) throws SQLException {
        si.beforePrepare(null, spy(real, rec), null);
        try (Statement st = real.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_setting('search_path')")) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** 动态代理包装连接：透传一切调用（含 unwrap），仅记录经 createStatement 执行的 SET 语句。 */
    private static Connection spy(Connection real, List<String> rec) {
        return (Connection) Proxy.newProxyInstance(
                SchemaInterceptorMemoTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if ("createStatement".equals(method.getName()) && result instanceof Statement st) {
                        return Proxy.newProxyInstance(
                                SchemaInterceptorMemoTest.class.getClassLoader(),
                                new Class<?>[]{Statement.class},
                                (p2, m2, a2) -> {
                                    if ("execute".equals(m2.getName()) && a2 != null && a2.length > 0
                                            && a2[0] instanceof String sql && sql.startsWith("SET")) {
                                        rec.add(sql);
                                    }
                                    try {
                                        return m2.invoke(st, a2);
                                    } catch (InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                });
                    }
                    return result;
                });
    }
}
