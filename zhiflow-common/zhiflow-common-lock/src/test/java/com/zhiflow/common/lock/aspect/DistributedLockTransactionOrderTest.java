package com.zhiflow.common.lock.aspect;

import com.zhiflow.common.lock.annotation.DistributedLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D66：{@code @DistributedLock} × {@code @Transactional} 的切面顺序行为固化。
 *
 * <h2>为什么这条顺序是资金安全的前提</h2>
 * <p>{@code OrderInfoServiceImpl#notifyOrder} 的幂等判据是「拿到锁之后重查 DB 的 is_pay」。
 * 这个判据只有在<b>锁一直持有到事务提交之后</b>才成立：
 * <ul>
 *   <li><b>锁在外（正确）</b>：加锁 → 开事务 → 业务 → <b>提交</b> → 解锁。
 *       后一个线程拿到锁时前一个已提交，重查读到 is_pay='1'，正确跳过。</li>
 *   <li><b>事务在外（错误）</b>：开事务 → 加锁 → 业务 → 解锁 → <b>提交</b>。
 *       锁在提交之前就放了：T1 解锁但未提交，T2 立刻拿锁、重查读到的还是旧的 is_pay='0'，
 *       判定"没处理过"，于是积分发两次、销量加两次、{@code OrderPaidEvent} 发两次
 *       —— 佣金也跟着发两次。</li>
 * </ul>
 *
 * <p>Spring 的事务 advisor 默认 {@code LOWEST_PRECEDENCE}；本切面此前<b>没写</b> {@code @Order}，
 * 同样是 {@code LOWEST_PRECEDENCE}，两者打平，实际顺序取决于 advisor 发现顺序 = 未定义行为。
 * 现在切面声明了 {@code @Order(HIGHEST_PRECEDENCE)}，本测试断言这条顺序真的生效。
 *
 * <p>断言的是<b>事件时序</b>而不是注解值：直接读 {@code @Order} 的值只能证明"写了这个注解"，
 * 证明不了 Spring 真的按它排序（比如另有一个更高优先级的事务 advisor）。
 * 时序断言在任何一种失效方式下都会红。
 *
 * <p>用自建的 {@link RecordingTransactionManager} 而不是 DataSourceTransactionManager：
 * 被测的是 advisor 排序，与数据库无关，不该为此把 spring-jdbc / H2 拖进本模块的测试依赖。
 */
class DistributedLockTransactionOrderTest {

    /** 全局事件流水，按发生顺序记录 */
    static final List<String> EVENTS = new ArrayList<>();

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        EVENTS.clear();
        context = new AnnotationConfigApplicationContext(TestConfig.class);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("锁必须在事务外层：加锁 → 开事务 → 业务 → 提交 → 解锁")
    void lockAdvisorRunsOutsideTransactionAdvisor() {
        context.getBean(MoneyService.class).payOnce("order-1");

        assertEquals(List.of("lock:acquire", "tx:begin", "biz", "tx:commit", "lock:release"), EVENTS,
                """
                切面顺序不对。期望「锁在最外层」，即解锁发生在事务提交之后。
                若实际是 [tx:begin, lock:acquire, biz, lock:release, tx:commit]，
                说明 DistributedLockAspect 的 @Order(HIGHEST_PRECEDENCE) 没生效
                （被删了？或者容器里另有优先级更高的事务 advisor），
                锁会在提交前释放，notifyOrder 的「锁内重查 DB」幂等判据随之失效
                —— 并发支付回调会重复发积分/销量/佣金。""");
    }

    @Test
    @DisplayName("业务抛异常时：先回滚、后解锁（顺序同样不能反）")
    void lockIsReleasedAfterRollback() {
        try {
            context.getBean(MoneyService.class).payAndFail("order-2");
        } catch (IllegalStateException expected) {
            // 预期抛出
        }
        assertEquals(List.of("lock:acquire", "tx:begin", "biz", "tx:rollback", "lock:release"), EVENTS);
    }

    /** 锁没抢到时业务一次都不该跑（FAIL_FAST 策略直接抛） */
    @Test
    @DisplayName("抢不到锁时业务不执行、事务不开启")
    void businessNotExecutedWhenLockNotAcquired() {
        context.getBean(LockProbe.class).acquirable = false;
        boolean threw = false;
        try {
            context.getBean(MoneyService.class).payOnce("order-3");
        } catch (RuntimeException e) {
            threw = true;
        }
        assertTrue(threw, "FAIL_FAST 策略下抢不到锁应当抛异常");
        assertEquals(List.of(), EVENTS, "抢不到锁时不该有任何事务或业务事件");
    }

    // ------------------------------------------------------------------ 装配

    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy
    static class TestConfig {

        @Bean
        LockProbe lockProbe() {
            return new LockProbe();
        }

        @Bean
        RedissonClient redissonClient(LockProbe probe) {
            RedissonClient client = mock(RedissonClient.class);
            RLock lock = mock(RLock.class);
            when(client.getLock(anyString())).thenReturn(lock);

            // tryLock 的两个重载都要桩：leaseTime=-1（看门狗）走 (waitTime, unit)，否则走 (wait, lease, unit)
            try {
                when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenAnswer(inv -> recordAcquire(probe));
                when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> recordAcquire(probe));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            when(lock.isHeldByCurrentThread()).thenAnswer(inv -> probe.held);
            org.mockito.Mockito.doAnswer(inv -> {
                probe.held = false;
                EVENTS.add("lock:release");
                return null;
            }).when(lock).unlock();
            return client;
        }

        private static Object recordAcquire(LockProbe probe) {
            if (!probe.acquirable) {
                return false;
            }
            probe.held = true;
            EVENTS.add("lock:acquire");
            return true;
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean
        MoneyService moneyService() {
            return new MoneyService();
        }

        @Bean
        DistributedLockAspect distributedLockAspect(RedissonClient redissonClient) {
            return new DistributedLockAspect(redissonClient);
        }
    }

    /** 让用例能控制「抢不抢得到锁」，并跟踪持有状态 */
    static class LockProbe {
        volatile boolean acquirable = true;
        volatile boolean held = false;
    }

    /** 只记录 begin/commit/rollback 的最小事务管理器——本测试不需要真连库 */
    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            EVENTS.add("tx:begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            EVENTS.add("tx:commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            EVENTS.add("tx:rollback");
        }
    }

    /** 同一方法上同时挂两个注解 —— 正是 notifyOrder / orderCancel / balancePay 的写法 */
    static class MoneyService {

        @Transactional
        @DistributedLock(name = "order", keys = {"#orderId"})
        public void payOnce(String orderId) {
            EVENTS.add("biz");
        }

        @Transactional
        @DistributedLock(name = "order", keys = {"#orderId"})
        public void payAndFail(String orderId) {
            EVENTS.add("biz");
            throw new IllegalStateException("boom");
        }
    }
}
