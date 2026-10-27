package com.zhiflow.mall.api.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.zhiflow.mall.api.mapper.ShoppingCartMapper;
import com.zhiflow.mall.common.entity.ShoppingCart;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加购 upsert（{@link ShoppingCartMapper#UPSERT_ADD_QUANTITY_SQL}）在真实 PostgreSQL 上的并发行为。
 *
 * <p>跑的就是 mapper 上那句 SQL，经 MyBatis 绑定，表结构与租户 schema 一致
 * （列、默认值、部分唯一索引 {@code WHERE del_flag = '0'}）；另建一个全量唯一索引的 schema 对应 base schema 的形态。
 *
 * <p><b>默认跳过，只许指向一次性库</b>（会建 / 删自己的 schema）：
 * {@code -Dcart.pg.url=jdbc:postgresql://127.0.0.1:55493/postgres -Dcart.pg.user=postgres}
 *
 * <p>对照样本：原来的「查 → 删 → 插」在同样的交错下撞唯一索引（SQLState 23505）——证明这套夹具看得见那个缺陷。
 */
@EnabledIfSystemProperty(named = "cart.pg.url", matches = ".+")
class ShoppingCartUpsertPgTest {

    private static final String TENANT = "1000000000000000002";
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private static final String PARTIAL = "cart_it_partial_" + SUFFIX;
    private static final String FULL = "cart_it_full_" + SUFFIX;

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime());

    private static SqlSessionFactory partialFactory;
    private static SqlSessionFactory fullFactory;

    @BeforeAll
    static void createSchemas() throws SQLException {
        try (Connection c = dataSource(null).getConnection(); Statement st = c.createStatement()) {
            for (String schema : new String[]{PARTIAL, FULL}) {
                st.execute("CREATE SCHEMA " + schema);
                // 列、类型、默认值与 mall_tenant_structure.sql 的 shopping_cart 一致
                st.execute("CREATE TABLE " + schema + ".shopping_cart ("
                        + " id character varying(32) NOT NULL PRIMARY KEY,"
                        + " tenant_id character varying(36) DEFAULT '1'::character varying,"
                        + " shop_id character varying(32) NOT NULL,"
                        + " del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,"
                        + " create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,"
                        + " update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,"
                        + " user_id character varying(32) NOT NULL,"
                        + " spu_id character varying(32) NOT NULL,"
                        + " sku_id character varying(32) NOT NULL,"
                        + " quantity integer NOT NULL,"
                        + " spu_name character varying(200),"
                        + " add_price numeric(10,2),"
                        + " spec_info character varying(200),"
                        + " pic_url character varying(500),"
                        + " page_type character varying(255))");
            }
            // 4 个租户 schema 的形态（V80 之后）
            st.execute("CREATE UNIQUE INDEX shopping_cart_tenant_id_user_id_spu_id_sku_id_idx ON " + PARTIAL
                    + ".shopping_cart USING btree (tenant_id, user_id, spu_id, sku_id) WHERE ((del_flag)::text = '0'::text)");
            // base schema zhiflow_mall 的形态（全量唯一索引）
            st.execute("CREATE UNIQUE INDEX idx_uk_user_spu_sku ON " + FULL
                    + ".shopping_cart USING btree (tenant_id, user_id, spu_id, sku_id)");
        }
        partialFactory = factory(PARTIAL);
        fullFactory = factory(FULL);
    }

    @AfterAll
    static void dropSchemas() throws SQLException {
        try (Connection c = dataSource(null).getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + PARTIAL + " CASCADE");
            st.execute("DROP SCHEMA IF EXISTS " + FULL + " CASCADE");
        }
    }

    @BeforeEach
    void truncate() throws SQLException {
        try (Connection c = dataSource(null).getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE " + PARTIAL + ".shopping_cart");
            st.execute("TRUNCATE " + FULL + ".shopping_cart");
        }
    }

    @Test
    @DisplayName("16 个并发加购同一 SKU：全部成功，只剩一行，数量 = 各次之和")
    void concurrentAddsAccumulate() throws Exception {
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ShoppingCart>> results = new ArrayList<>();
        try {
            for (int i = 1; i <= n; i++) {
                int qty = i;
                results.add(pool.submit(() -> {
                    start.await();
                    return add(partialFactory, qty, "rt-" + qty);
                }));
            }
            start.countDown();
            for (Future<ShoppingCart> f : results) {
                f.get(30, TimeUnit.SECONDS);   // 任何一个撞唯一索引都会在这里抛出
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count(PARTIAL, "del_flag = '0'"));
        assertEquals(n * (n + 1) / 2, quantity(PARTIAL));
    }

    @Test
    @DisplayName("确定性交错：第二个请求在第一个未提交时发出，等其提交后走累加分支，行 id 不变")
    void secondWriterWaitsThenAccumulates() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (SqlSession first = partialFactory.openSession(false)) {
            ShoppingCart r1 = first.getMapper(ShoppingCartMapper.class).upsertAddQuantity(cart(2, null));
            // 第一个事务未提交：第二个 upsert 会卡在唯一索引上
            Future<ShoppingCart> second = pool.submit(() -> add(partialFactory, 3, null));
            Thread.sleep(300);
            assertTrue(!second.isDone(), "第二个写应当在唯一索引上等第一个事务");
            first.commit(true);
            ShoppingCart r2 = second.get(30, TimeUnit.SECONDS);
            assertEquals(r1.getId(), r2.getId());
            assertEquals(5, r2.getQuantity());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count(PARTIAL, "true"));
    }

    @Test
    @DisplayName("冲突分支：快照列取本次请求（同原来的删旧插新），数量累加")
    void conflictBranchReplacesSnapshotColumns() throws Exception {
        add(partialFactory, 1, "rt-a");
        ShoppingCart second = cart(4, "rt-b");
        second.setSpuName("新名字");
        try (SqlSession s = partialFactory.openSession(false)) {
            s.getMapper(ShoppingCartMapper.class).upsertAddQuantity(second);
            s.commit(true);
        }
        try (Connection c = dataSource(PARTIAL).getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT quantity, spu_name, page_type, tenant_id FROM shopping_cart");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(5, rs.getInt(1));
            assertEquals("新名字", rs.getString(2));
            assertEquals("rt-b", rs.getString(3));
            assertEquals(TENANT, rs.getString(4));
        }
    }

    @Test
    @DisplayName("软删墓碑不参与冲突：墓碑之外新插一行活跃行")
    void tombstoneDoesNotConflict() throws Exception {
        try (Connection c = dataSource(PARTIAL).getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO shopping_cart (id, tenant_id, shop_id, del_flag, user_id, spu_id, sku_id, quantity)"
                    + " VALUES ('tomb-1', '" + TENANT + "', 'shop-1', '1', 'u-1', 'spu-1', 'sku-1', 9)");
        }
        add(partialFactory, 2, null);
        assertEquals(1, count(PARTIAL, "del_flag = '0'"));
        assertEquals(2, quantity(PARTIAL));
    }

    @Test
    @DisplayName("base schema 的全量唯一索引：同一句 SQL 同样能推断、同样累加")
    void fullUniqueIndexAlsoWorks() throws Exception {
        add(fullFactory, 1, null);
        add(fullFactory, 2, null);
        assertEquals(1, count(FULL, "true"));
        assertEquals(3, quantity(FULL));
    }

    @Test
    @DisplayName("对照样本：原来的「查 → 删 → 插」在同样的并发下撞唯一索引（23505）")
    void legacyReadDeleteInsertCollides() throws Exception {
        CyclicBarrier bothRead = new CyclicBarrier(2);
        AtomicInteger duplicateKey = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int qty = i + 1;
                fs.add(pool.submit(() -> {
                    try {
                        legacyAdd(qty, bothRead);
                    } catch (SQLException e) {
                        if ("23505".equals(e.getSQLState())) {
                            duplicateKey.incrementAndGet();
                        } else {
                            throw new IllegalStateException(e);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, duplicateKey.get(), "修复前的写法在这个交错下必有一个 duplicate key（表现为 500）");
    }

    // ---------------------------------------------------------------- 夹具

    /** 原 ShoppingCartServiceImpl#save 的数据库动作：查同 SKU 活跃行 → 有则物理删并把数量并进来 → 插新行 */
    private static void legacyAdd(int qty, CyclicBarrier afterRead) throws SQLException {
        try (Connection c = dataSource(PARTIAL).getConnection()) {
            c.setAutoCommit(false);
            int total = qty;
            String oldId = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT id, quantity FROM shopping_cart WHERE tenant_id = ?"
                    + " AND user_id = 'u-1' AND spu_id = 'spu-1' AND sku_id = 'sku-1' AND del_flag = '0'")) {
                ps.setString(1, TENANT);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        oldId = rs.getString(1);
                        total += rs.getInt(2);
                    }
                }
            }
            try {
                afterRead.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            if (oldId != null) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM shopping_cart WHERE id = ?")) {
                    ps.setString(1, oldId);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO shopping_cart (id, tenant_id, shop_id, user_id, spu_id,"
                    + " sku_id, quantity) VALUES (?, ?, 'shop-1', 'u-1', 'spu-1', 'sku-1', ?)")) {
                ps.setString(1, String.valueOf(IDS.incrementAndGet()));
                ps.setString(2, TENANT);
                ps.setInt(3, total);
                ps.executeUpdate();
            }
            c.commit();
        }
    }

    /**
     * 注意 {@code commit(true)}：upsert 挂在 {@code @Select} 上（为了拿 RETURNING），裸 MyBatis 认为它不脏、
     * {@code commit()} 是空操作。生产里提交由 Spring 事务管理器做（{@code save} 带 {@code @Transactional}），
     * 事务外 mybatis-spring 也是 {@code commit(true)}，不受这个影响。
     */
    private static ShoppingCart add(SqlSessionFactory f, int qty, String rt) {
        try (SqlSession s = f.openSession(false)) {
            ShoppingCart r = s.getMapper(ShoppingCartMapper.class).upsertAddQuantity(cart(qty, rt));
            s.commit(true);
            return r;
        }
    }

    private static ShoppingCart cart(int qty, String pageType) {
        LocalDateTime now = LocalDateTime.now();
        ShoppingCart c = new ShoppingCart();
        c.setId(String.valueOf(IDS.incrementAndGet()));
        c.setTenantId(TENANT);
        c.setShopId("shop-1");
        c.setUserId("u-1");
        c.setSpuId("spu-1");
        c.setSkuId("sku-1");
        c.setQuantity(qty);
        c.setSpuName("维生素 C");
        c.setCreateTime(now);
        c.setUpdateTime(now);
        c.setPageType(pageType);
        return c;
    }

    private static long count(String schema, String where) throws SQLException {
        try (Connection c = dataSource(schema).getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM shopping_cart WHERE " + where)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static int quantity(String schema) throws SQLException {
        try (Connection c = dataSource(schema).getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT quantity FROM shopping_cart WHERE del_flag = '0'")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static SqlSessionFactory factory(String schema) {
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.setEnvironment(new Environment("cart-it", new JdbcTransactionFactory(), dataSource(schema)));
        cfg.addMapper(ShoppingCartMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(cfg);
    }

    private static PGSimpleDataSource dataSource(String schema) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(System.getProperty("cart.pg.url"));
        ds.setUser(System.getProperty("cart.pg.user", "postgres"));
        ds.setPassword(System.getProperty("cart.pg.password", ""));
        if (schema != null) {
            ds.setCurrentSchema(schema);
        }
        return ds;
    }
}
