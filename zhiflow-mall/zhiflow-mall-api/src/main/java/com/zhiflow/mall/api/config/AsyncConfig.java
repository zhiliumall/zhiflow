package com.zhiflow.mall.api.config;

import com.alibaba.ttl.TtlRunnable;
import com.alibaba.ttl.threadpool.TtlExecutors;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 异步任务和线程池配置类
 * <p>
 * 提供四种线程池：
 * 1. asyncExecutor - 传统线程池，供 @Async("asyncExecutor")（交易埋点）使用，满了丢弃并计数
 * 2. virtualThreadExecutor - 生产级虚拟线程池（Java 21+），用于I/O密集型并发查询
 * 3. dbFanoutExecutor - 有界连接预算执行器，供并行 DB 聚合查询使用
 * 4. bizAsyncExecutor - 业务 @Async 执行器（B2，10-18），接管所有<b>不带名字</b>的 @Async；提交即为每个任务开一条虚拟线程
 *    （提交方永不阻塞），线程内拿名额（默认 2000）再执行，停机等已提交任务最多 30 秒；
 *    见 {@link #getAsyncExecutor()}、{@link #bizAsyncExecutor(ObjectProvider, int)}
 * </p>
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /** asyncExecutor 丢弃任务的计数指标（tag executor=asyncExecutor）。 */
    static final String M_ASYNC_REJECTED = "mall_async_rejected_total";

    /** bizAsyncExecutor 拿到名额正在执行的任务数 gauge（tag executor=bizAsyncExecutor）。 */
    static final String M_BIZ_ASYNC_ACTIVE = "mall_biz_async_active";

    /** bizAsyncExecutor 已提交、在虚拟线程里等名额的任务数 gauge（tag executor=bizAsyncExecutor）。 */
    static final String M_BIZ_ASYNC_WAITING = "mall_biz_async_waiting";

    /** bizAsyncExecutor 需要等名额的次数（tag executor=bizAsyncExecutor）。 */
    static final String M_BIZ_ASYNC_THROTTLED = "mall_biz_async_throttled_total";

    /** bizAsyncExecutor 等名额的时长（Timer，tag executor=bizAsyncExecutor）。 */
    static final String M_BIZ_ASYNC_THROTTLE_WAIT = "mall_biz_async_throttle_wait";

    /** bizAsyncExecutor 停机等待超时、仍在等名额而未执行即放弃的任务数（tag executor=bizAsyncExecutor）。 */
    static final String M_BIZ_ASYNC_ABANDONED = "mall_biz_async_abandoned_total";

    /**
     * bizAsyncExecutor 的 TTL 上下文传播装饰器。
     * <p>
     * 语义与 {@code asyncExecutor} 用的 {@code TtlExecutors.getTtlExecutor(...)} 外层包装完全一致
     * （已用字节码核对 {@code ExecutorTtlWrapper.execute()} 实际调的是
     * {@code TtlRunnable.get(runnable, false, true)}：releaseTtlValueReferenceAfterRun=false、
     * idempotent=true）。bizAsyncExecutor 要原样注册成 Spring bean（停机时由容器调 {@code close()} 等在跑任务），
     * 不能外面再裹一层只实现 {@code Executor}、没有生命周期的 {@code ExecutorTtlWrapper}，所以改成任务级装饰：
     * {@code SimpleAsyncTaskExecutor.execute()} 在<i>提交方线程</i>里调 {@code decorate}，抓的是提交那一刻的上下文
     * （bizAsyncExecutor 在它外面再套一层名额包装，见 {@link BizAsyncTaskExecutor}）。
     * </p>
     */
    static final TaskDecorator TTL_TASK_DECORATOR = runnable -> TtlRunnable.get(runnable, false, true);

    /**
     * 延迟引用 {@code bizAsyncExecutor} bean（见 {@link #getAsyncExecutor()} 的用法说明）。
     * <p>
     * 用字段注入 {@code ObjectProvider} 而不是构造器注入普通 {@code Executor}：本类实现了
     * {@link AsyncConfigurer}，Spring 在 {@code registerBeanPostProcessors()} 阶段（早于绝大多数
     * 常规单例 bean 的创建）就要扫描全部 {@code AsyncConfigurer} 类型 bean 以装配
     * {@code AsyncAnnotationBeanPostProcessor}，这会提前触发 {@code AsyncConfig} 自身的实例化。
     * 若此处改成构造器注入具体的 {@code Executor} bean，会连带提前触发 {@code bizAsyncExecutor}
     * （及其依赖的 {@code MeterRegistry}）在同一早期阶段创建，徒增上下文启动期的 bean 创建顺序风险。
     * {@code ObjectProvider.getObject()} 把这次解析推迟到第一次真正有 {@code @Async} 方法被调用时
     * （此时上下文早已启动完毕），该写法是 Spring 官方文档推荐的标准做法。
     * </p>
     */
    @Autowired
    @Qualifier("bizAsyncExecutor")
    private ObjectProvider<Executor> bizAsyncExecutorProvider;

    /**
     * 让所有<b>不带名字</b>的 {@code @Async} 方法走 {@code bizAsyncExecutor}（B2，10-18：虚拟线程 + 并发上限）。
     * <p>
     * 零影响范围：{@code TradeEventEmitter} 三个方法显式写了 {@code @Async("asyncExecutor")}，
     * 永远按 bean 名精确指定执行器，不受本方法影响；{@code virtualThreadExecutor}/
     * {@code dbFanoutExecutor} 从未被当作 {@code @Async} 默认执行器用过（都靠 {@code @Qualifier}
     * 按名字注入，不参与 Spring 对"唯一 Executor/TaskExecutor bean"的隐式查找），不受影响。
     * </p>
     * <p>
     * {@link #getAsyncUncaughtExceptionHandler()} 不重写：保持现状——Spring 在
     * {@code AsyncConfigurer} 存在但未重写该方法时，仍回退到
     * {@code SimpleAsyncUncaughtExceptionHandler}，与改动前（完全没有 {@code AsyncConfigurer}）
     * 效果一致。
     * </p>
     */
    @Override
    public Executor getAsyncExecutor() {
        return bizAsyncExecutorProvider.getObject();
    }

    /**
     * 业务 @Async 执行器（B2）：提交时立即为每个任务开一条虚拟线程（<b>提交方永不阻塞</b>），虚拟线程里先拿并发名额
     * （默认 2000，配置项 {@code zhiflow.biz-async.concurrency-limit}，改了要重启）再执行任务，finally 归还。
     * <p>
     * <b>演变</b>：
     * <ol>
     *   <li>32 线程 + 1 万队列 + CallerRuns：10-18 19:20–20:22 压测只下单 1500/1800 单/秒、热点 500 单/秒时被打满，两台
     *   CallerRuns 共约 18 万次。CallerRuns 让 {@code OrderEventListener} 这类 {@code @Async + @TransactionalEventListener(AFTER_COMMIT)}
     *   监听器在请求线程的 {@code triggerAfterCompletion} 里跑（Spring 7 的 AFTER_COMMIT 监听在这里执行：同步回调已清空、
     *   {@code isActualTransactionActive()} 仍为 true），在该阶段里再注册新的事务同步回调会抛
     *   {@code IllegalStateException: Transaction synchronization is not active}，导致大量下单后续的异步副作用没有执行。</li>
     *   <li>Spring 自带的 {@code setConcurrencyLimit}（满了提交方阻塞）：提交方是请求线程，阻塞时还攥着下单事务的 DB 连接
     *   （afterCompletion 之后才归还），而占着名额的任务可能正在等 DB 连接，会互等到 Druid {@code max-wait} 超时。没上线即改掉。</li>
     *   <li>现在：名额在虚拟线程<i>内部</i>拿（与 {@link #dbFanoutExecutor} 同一写法），提交方只负责起一条虚拟线程就返回；
     *   被挡住的是廉价的停放虚拟线程，「排队」就是这些停放的线程。</li>
     * </ol>
     * </p>
     * <p>
     * <b>排队无上限</b>：没有队列长度上限，持续过载时停放的虚拟线程会一直涨（10-18 第四轮审查实测：只算虚拟线程 + 停放栈 + FutureTask + TTL 快照，每条约 2.1 KB 堆；
     * 再加 {@code @Async} 捕获的方法参数（订单事件带整个 OrderInfo），按 3–5 KB 估。1 GiB 堆大约 10 万条就危险）。
     * 用 {@value #M_BIZ_ASYNC_WAITING} 盯着；它持续上涨说明异步副作用的处理速度跟不上下单速度。
     * </p>
     * <p>
     * <b>停机</b>：本执行器实现 {@link SmartLifecycle}，在生命周期停止阶段（phase {@value #BIZ_ASYNC_SHUTDOWN_PHASE}）调 {@code close()}：
     * 晚于 gRPC（phase {@code Integer.MAX_VALUE}）和 Web 的优雅停机 / 停服（{@code MAX_VALUE-1024} / {@code MAX_VALUE-2048}），
     * 所以请求都排空了才开始收尾；早于 Valkey 的 {@code LettuceConnectionFactory}（SmartLifecycle，phase 0，停了就拒绝取连接），
     * 也早于整个销毁单例阶段（Druid 数据源、{@code mqProducer} 的 {@code shutdown} 都在那里）。如果只靠 {@code AutoCloseable} 的
     * destroyMethod 推断在销毁阶段 close，这 30 秒里 Redis 已经停了、数据源 / MQ producer 是否还在取决于 bean 创建顺序，
     * 「等已提交任务跑完」就成了空话（10-18 第四轮审查）。35s + 30s 仍在 graceful-rollout 的 90s 内。{@code close()} 之后：
     * <ul>
     *   <li>新提交直接抛 {@code TaskRejectedException}；</li>
     *   <li>已提交的任务——正在执行的和正在等名额的（等名额的虚拟线程同样登记在 Spring 的活动线程集合里）——最多一起等 30 秒，
     *   等名额的照常拿到名额后执行；</li>
     *   <li>30 秒到点：仍在执行的被 interrupt（任务自己决定怎么收尾）；<b>仍在等名额的被 interrupt 后不执行</b>（{@code submit} 的
     *   Future 被 cancel），计入 {@value #M_BIZ_ASYNC_ABANDONED} 并打 WARN。这些任务就丢了：延迟关单消息有兜底关单任务和懒补偿兜底，
     *   通知类没有兜底；</li>
     *   <li>{@code close()} 那一刻刚起、还没跑到登记那一步的虚拟线程，Spring 在它登记时发现已取消就直接抛 {@code CancellationException}，
     *   也不执行（极窄的竞争窗口）。</li>
     * </ul>
     * </p>
     * <p>
     * <b>上下文</b>：{@link #TTL_TASK_DECORATOR} 在提交方线程里抓 TTL（{@code TenantContextHolder} 及其 {@code TenantMdcTransmittee}
     * 带的 MDC {@code tenant.id}），拿到名额后在虚拟线程里回放、跑完还原；虚拟线程不复用，任务里 {@code TenantContextHolder.clear()}
     * 与否都不会串到下一个任务。OTel javaagent（2.31.1）自带 {@code SimpleAsyncTaskExecutorInstrumentation}，在
     * {@code doExecute(Runnable)}（提交方线程）上包 {@code ContextPropagatingRunnable}，trace 上下文照传。Java 25 已无 synchronized
     * 钉住载体线程的问题（JEP 491，JDK 24 起）。
     * </p>
     * <p>
     * <b>可观测</b>（tag {@code executor=bizAsyncExecutor}）：{@value #M_BIZ_ASYNC_ACTIVE}（拿到名额正在执行）、
     * {@value #M_BIZ_ASYNC_WAITING}（已提交、在等名额）、{@value #M_BIZ_ASYNC_THROTTLED}（需要等名额的次数）、
     * {@value #M_BIZ_ASYNC_THROTTLE_WAIT}（等名额时长）、{@value #M_BIZ_ASYNC_ABANDONED}（停机超时未执行即放弃）。
     * </p>
     */
    @Bean("bizAsyncExecutor")
    public BizAsyncTaskExecutor bizAsyncExecutor(ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${zhiflow.biz-async.concurrency-limit:" + BIZ_ASYNC_DEFAULT_LIMIT + "}") int concurrencyLimit) {
        int limit = concurrencyLimit;
        if (limit < 1) {
            log.warn("zhiflow.biz-async.concurrency-limit={} 无效（必须 >= 1），按默认 {} 处理", concurrencyLimit,
                    BIZ_ASYNC_DEFAULT_LIMIT);
            limit = BIZ_ASYNC_DEFAULT_LIMIT;
        }
        return newBizAsyncExecutor(limit, meterRegistry.getIfAvailable());
    }

    /** 默认并发上限。 */
    static final int BIZ_ASYNC_DEFAULT_LIMIT = 2000;

    /**
     * 停机收尾的生命周期阶段：必须低于 Web 停服（{@code SmartLifecycle.DEFAULT_PHASE - 2048}）、高于 Lettuce（0）。
     * 生命周期按 phase 从高到低停。
     */
    static final int BIZ_ASYNC_SHUTDOWN_PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    /** 停机时等已提交任务的上限（毫秒）。 */
    static final long BIZ_ASYNC_TERMINATION_TIMEOUT_MILLIS = 30_000;

    /** 拼装 bizAsyncExecutor；抽出来是为了单测能把上限调小后打满。 */
    static BizAsyncTaskExecutor newBizAsyncExecutor(int concurrencyLimit, MeterRegistry registry) {
        BizAsyncTaskExecutor executor = new BizAsyncTaskExecutor(concurrencyLimit, registry);
        executor.setTaskTerminationTimeout(BIZ_ASYNC_TERMINATION_TIMEOUT_MILLIS);
        return executor;
    }

    /**
     * 虚拟线程 + 线程内名额。不用 Spring 的 {@code setConcurrencyLimit}（那是在提交方线程拿名额、满了阻塞提交方）。
     * <p>
     * 名额包装放在 {@code TaskDecorator} 里：Spring 先套 decorator、再套它自己的活动线程登记（{@code TaskTrackingRunnable}），
     * 所以等名额的虚拟线程也在登记集合里，{@code close()} 会连它们一起等、超时一起 interrupt。
     * </p>
     * <p>
     * 与 {@link #dbFanoutExecutor} 一样在虚拟线程里 acquire、finally release；区别是这里用<b>公平</b>信号量，并且用
     * {@code tryAcquire(0, ...)} 判断要不要等（无参 {@code tryAcquire()} 在公平模式下也会插队）：持续过载时先提交的副作用
     * （比如自动关单延迟消息）先执行，不会被后来的任务一直插队饿死。
     * </p>
     */
    public static final class BizAsyncTaskExecutor extends SimpleAsyncTaskExecutor implements SmartLifecycle {

        private final int concurrencyLimit;

        private final Semaphore permits;

        private final AtomicInteger active = new AtomicInteger();

        private final AtomicInteger waiting = new AtomicInteger();

        private final AtomicLong throttledLocal = new AtomicLong();

        private final AtomicLong abandonedLocal = new AtomicLong();

        private final Counter throttled;

        private final Counter abandoned;

        private final Timer throttleWait;

        BizAsyncTaskExecutor(int concurrencyLimit, MeterRegistry registry) {
            super("mall-biz-async-");
            this.concurrencyLimit = concurrencyLimit;
            this.permits = new Semaphore(concurrencyLimit, true);
            setVirtualThreads(true);
            // 外层名额包装在虚拟线程里执行；内层 TTL 装饰在这里（提交方线程）抓上下文
            setTaskDecorator(task -> withPermit(TTL_TASK_DECORATOR.decorate(task), task instanceof Future<?> f ? f : null));
            if (registry != null) {
                Gauge.builder(M_BIZ_ASYNC_ACTIVE, active, AtomicInteger::get)
                        .tag("executor", "bizAsyncExecutor").register(registry);
                Gauge.builder(M_BIZ_ASYNC_WAITING, waiting, AtomicInteger::get)
                        .tag("executor", "bizAsyncExecutor").register(registry);
                this.throttled = Counter.builder(M_BIZ_ASYNC_THROTTLED)
                        .tag("executor", "bizAsyncExecutor").register(registry);
                this.abandoned = Counter.builder(M_BIZ_ASYNC_ABANDONED)
                        .tag("executor", "bizAsyncExecutor").register(registry);
                this.throttleWait = Timer.builder(M_BIZ_ASYNC_THROTTLE_WAIT)
                        .tag("executor", "bizAsyncExecutor").register(registry);
            }
            else {
                this.throttled = null;
                this.abandoned = null;
                this.throttleWait = null;
            }
        }

        private Runnable withPermit(Runnable task, Future<?> future) {
            return () -> {
                if (!acquirePermit()) {
                    if (future != null) {
                        future.cancel(false);
                    }
                    return;
                }
                active.incrementAndGet();
                try {
                    task.run();
                }
                finally {
                    active.decrementAndGet();
                    permits.release();
                }
            };
        }

        /** 拿名额（可能要等）。等的过程中被中断（只有停机超时会这样）返回 false：任务不执行。 */
        private boolean acquirePermit() {
            try {
                if (permits.tryAcquire(0, TimeUnit.NANOSECONDS)) {
                    return true;
                }
            }
            catch (InterruptedException e) {
                return abandon();
            }
            long n = throttledLocal.incrementAndGet();
            if (throttled != null) {
                throttled.increment();
            }
            if (n % 1000 == 1) {
                log.warn("bizAsyncExecutor 已到并发上限 {}，任务在自己的虚拟线程里等名额（提交方不等），累计={}，当前在等={}",
                        concurrencyLimit, n, waiting.get() + 1);
            }
            waiting.incrementAndGet();
            long start = System.nanoTime();
            try {
                permits.acquire();
                return true;
            }
            catch (InterruptedException e) {
                return abandon();
            }
            finally {
                waiting.decrementAndGet();
                if (throttleWait != null) {
                    throttleWait.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                }
            }
        }

        private boolean abandon() {
            Thread.currentThread().interrupt();
            long n = abandonedLocal.incrementAndGet();
            if (abandoned != null) {
                abandoned.increment();
            }
            if (n % 1000 == 1) {
                log.warn("bizAsyncExecutor 停机等待超时，仍在等名额的任务未执行即放弃，累计={}", n);
            }
            return false;
        }

        // ---- SmartLifecycle：提前到生命周期停止阶段收尾（见 bizAsyncExecutor 的「停机」说明） ----

        /** 构造即可用，没有单独的启动动作。 */
        @Override
        public void start() {
        }

        /** 停止 = {@code close()}：拒绝新提交，等已提交任务（含等名额的）最多 30 秒；之后销毁阶段再调 {@code close()} 是空操作。 */
        @Override
        public void stop() {
            close();
        }

        @Override
        public boolean isRunning() {
            return isActive();
        }

        @Override
        public int getPhase() {
            return BIZ_ASYNC_SHUTDOWN_PHASE;
        }

        /** {@code close()} 不可逆，暂停（{@code context.pause()} / CRaC）时不能跟着关。 */
        @Override
        public boolean isPauseable() {
            return false;
        }

        /** 并发上限（Spring 自带的 {@code getConcurrencyLimit()} 没用上，恒为 -1）。 */
        int permitLimit() {
            return concurrencyLimit;
        }

        /** 拿到名额正在执行的任务数（同 {@value AsyncConfig#M_BIZ_ASYNC_ACTIVE}）。 */
        int activeCount() {
            return active.get();
        }

        /** 已提交、在等名额的任务数（同 {@value AsyncConfig#M_BIZ_ASYNC_WAITING}）。 */
        int waitingCount() {
            return waiting.get();
        }

        /** 需要等名额的次数（同 {@value AsyncConfig#M_BIZ_ASYNC_THROTTLED}）。 */
        long throttledCount() {
            return throttledLocal.get();
        }

        /** 停机超时未执行即放弃的任务数（同 {@value AsyncConfig#M_BIZ_ASYNC_ABANDONED}）。 */
        long abandonedCount() {
            return abandonedLocal.get();
        }
    }

    /**
     * 传统线程池执行器，供显式写了 {@code @Async("asyncExecutor")} 的方法使用。
     * <p>
     * 目前只有 {@code TradeEventEmitter} 的三个埋点方法用它（不写名字的 {@code @Async} 不走这里）。
     * 拒绝策略是「丢弃 + 计数」而不是 AbortPolicy：10-18 压测每秒 1000 单时这个池拒绝约 1.7 万次，
     * 每次都在下单请求线程的 afterCompletion 里抛 TaskRejectedException、打一条 ERROR 加整段堆栈；
     * 埋点丢了只影响统计，不值得这样。也不用 CallerRuns：AFTER_COMMIT 监听跑在 afterCompletion 里，
     * 那时请求线程还攥着下单事务的连接，再在它上面跑埋点只会把连接占得更久（连接池当时已经耗尽）。
     * </p>
     */
    @Bean("asyncExecutor")
    public Executor asyncExecutor(ObjectProvider<MeterRegistry> meterRegistry) {
        ThreadPoolTaskExecutor executor = newAsyncExecutor(4, 16, 2048,
                new CountingDiscardPolicy(meterRegistry.getIfAvailable()));
        // TTL 包装：自动传播 TransmittableThreadLocal（如 TenantContextHolder）到异步线程。
        // 包装器只把任务包成 TtlRunnable 再交给内层 ThreadPoolExecutor，拒绝策略设在内层上照样生效
        return TtlExecutors.getTtlExecutor(executor.getThreadPoolExecutor());
    }

    /** 拼装 asyncExecutor 的内层线程池；抽出来是为了单测能把池调小后打满。 */
    static ThreadPoolTaskExecutor newAsyncExecutor(int core, int max, int queue, RejectedExecutionHandler handler) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setRejectedExecutionHandler(handler);
        executor.initialize();
        return executor;
    }

    /**
     * 「丢弃新任务」+ 可观测：计数进 {@value #M_ASYNC_REJECTED}，每丢 1000 次打一行 WARN（第 1 次也打）。
     * 不抛异常。池已关停时直接丢、不计数（发版停机时的丢弃不是过载）。
     * <p>
     * 收到的任务是 TtlRunnable 包着的 FutureTask，认不出是哪个业务方法，所以只计总数。
     * </p>
     */
    static final class CountingDiscardPolicy implements RejectedExecutionHandler {

        private final AtomicLong rejected = new AtomicLong();

        private final Counter counter;

        CountingDiscardPolicy(MeterRegistry registry) {
            this.counter = registry == null ? null
                    : Counter.builder(M_ASYNC_REJECTED).tag("executor", "asyncExecutor").register(registry);
        }

        @Override
        public void rejectedExecution(Runnable runnable, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                return;
            }
            long n = rejected.incrementAndGet();
            if (counter != null) {
                counter.increment();
            }
            if (n % 1000 == 1) {
                log.warn("asyncExecutor 已满，丢弃埋点任务，累计丢弃={}（pool={}, active={}, queued={}）",
                        n, executor.getPoolSize(), executor.getActiveCount(), executor.getQueue().size());
            }
        }

        long rejected() {
            return rejected.get();
        }
    }
    
    /**
     * 生产级虚拟线程执行器(Java 21+)
     * <p>
     * 核心优化:
     * 1. 线程命名:带序号前缀 "vio-",便于日志 grep 和追踪
     * 2. 异常处理:统一捕获未处理异常并记录日志,避免静默失败
     * 3. 流量控制:使用 Semaphore 限制虚拟线程数量上限(默认 100,000),防止 OOM 和文件描述符耗尽
     * 4. 快速失败:达到上限时立即抛出 RejectedExecutionException,而非阻塞等待
     * 5. 资源释放:任务完成后自动释放信号量,确保计数准确
     * </p>
     * <p>
     * 适用场景:
     * - 数据库并行查询(商品详情、分类、库存等多表联查)
     * - 微服务并行调用(订单、支付、物流等服务聚合)
     * - 文件I/O操作(批量文件读写、上传下载)
     * - 网络请求(第三方API调用、HTTP客户端请求)
     * </p>
     * <p>
     * 注意:不适合CPU密集型计算任务,此类任务应使用传统线程池
     * </p>
     * 
     * @return 生产级虚拟线程执行器
     */
    @Bean("virtualThreadExecutor")
    public ExecutorService virtualThreadExecutor() {
        // 虚拟线程数量上限,根据压测结果调整(建议范围:50k-200k)
        int maxVirtualThreads = 100_000;
            
        // 信号量控制虚拟线程并发量,防止资源耗尽
        Semaphore throttle = new Semaphore(maxVirtualThreads);
            
        // 创建虚拟线程工厂:短名称 + 统一异常处理
        ThreadFactory factory = Thread.ofVirtual()
                .name("vio-", 0)  // 名字短、易 grep
                .uncaughtExceptionHandler((thread, throwable) -> 
                        log.error("Virtual thread {} died unexpectedly", thread.getName(), throwable))
                .factory();
            
        // 创建基础虚拟线程执行器
        ExecutorService baseExecutor = Executors.newThreadPerTaskExecutor(factory);
            
        // 包装执行器,增加流量控制和监控
        return new ExecutorService() {
            @Override
            public void execute(Runnable task) {
                // 快速失败:达到上限立即拒绝,避免 OOM
                if (!throttle.tryAcquire()) {
                    throw new RejectedExecutionException(
                            "Virtual thread quota exceeded: " + maxVirtualThreads + 
                            ". Consider increasing maxVirtualThreads or optimizing task concurrency.");
                }
                    
                // 提交任务,并确保完成后释放信号量
                baseExecutor.execute(() -> {
                    try {
                        task.run();
                    } finally {
                        throttle.release();
                    }
                });
            }
                
            @Override
            public void shutdown() {
                baseExecutor.shutdown();
            }
                
            @Override
            public java.util.List<Runnable> shutdownNow() {
                return baseExecutor.shutdownNow();
            }
                
            @Override
            public boolean isShutdown() {
                return baseExecutor.isShutdown();
            }
                
            @Override
            public boolean isTerminated() {
                return baseExecutor.isTerminated();
            }
                
            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return baseExecutor.awaitTermination(timeout, unit);
            }
                
            @Override
            public <T> Future<T> submit(Callable<T> task) {
                return baseExecutor.submit(wrapCallable(task));
            }
                
            @Override
            public <T> Future<T> submit(Runnable task, T result) {
                return baseExecutor.submit(wrapRunnable(task), result);
            }
                
            @Override
            public Future<?> submit(Runnable task) {
                return baseExecutor.submit(wrapRunnable(task));
            }
                
            @Override
            public <T> java.util.List<Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> tasks) 
                    throws InterruptedException {
                return baseExecutor.invokeAll(tasks.stream()
                        .map(this::wrapCallable)
                        .toList());
            }
                
            @Override
            public <T> java.util.List<Future<T>> invokeAll(
                    java.util.Collection<? extends Callable<T>> tasks, 
                    long timeout, 
                    TimeUnit unit) throws InterruptedException {
                return baseExecutor.invokeAll(
                        tasks.stream().map(this::wrapCallable).toList(), 
                        timeout, 
                        unit);
            }
                
            @Override
            public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks) 
                    throws InterruptedException, ExecutionException {
                return baseExecutor.invokeAny(tasks.stream()
                        .map(this::wrapCallable)
                        .toList());
            }
                
            @Override
            public <T> T invokeAny(
                    java.util.Collection<? extends Callable<T>> tasks, 
                    long timeout, 
                    TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                return baseExecutor.invokeAny(
                        tasks.stream().map(this::wrapCallable).toList(), 
                        timeout, 
                        unit);
            }
                
            private Runnable wrapRunnable(Runnable task) {
                return () -> {
                    if (!throttle.tryAcquire()) {
                        throw new RejectedExecutionException(
                                "Virtual thread quota exceeded: " + maxVirtualThreads);
                    }
                    try {
                        task.run();
                    } finally {
                        throttle.release();
                    }
                };
            }
                
            private <T> Callable<T> wrapCallable(Callable<T> task) {
                return () -> {
                    if (!throttle.tryAcquire()) {
                        throw new RejectedExecutionException(
                                "Virtual thread quota exceeded: " + maxVirtualThreads);
                    }
                    try {
                        return task.call();
                    } finally {
                        throttle.release();
                    }
                };
            }
        };
    }

    /**
     * 有界连接预算执行器（面向"并行 DB 聚合查询"）
     * <p>
     * 背景：聚合接口（商品详情 {@code getGoodsDetailAggregate}、用户面板 {@code UserDashboard}）的并行
     * fan-out 中，每个并行任务各借 1 条 DB 连接，期间连接被一直持有到 {@code allOf().join()}。
     * 直接复用 {@code virtualThreadExecutor}（对 DB 连接池近乎无界）时，
     * "并发请求数 × 每请求 fan-out 路数" 会远超 Druid {@code spring.datasource.dynamic.druid.max-active}(当前 30)，
     * 多余任务全部 park 在 {@code DruidDataSource.pollLast} 等连接，曾在 {@code max-wait=-1} 下永久挂起、无声雪崩。
     * </p>
     * <p>
     * 本执行器在 {@code virtualThreadExecutor} 之上叠加一个全局小信号量 {@code Semaphore(dbFanoutPermits)}：
     * <ul>
     *   <li>每个任务<b>在派生出的虚拟线程内部</b> {@code acquire()}，try 内执行任务，finally {@code release()}；
     *       <b>绝不在提交线程（调用方 / XNIO 主请求线程）里 acquire</b>，避免阻塞请求线程。</li>
     *   <li>效果：全局同时借用 DB 连接的 fan-out 任务数 ≤ {@code dbFanoutPermits}；超出的任务廉价 park
     *       在信号量上（虚拟线程 park 极廉价），<b>不会再涌入 {@code DruidDataSource.pollLast}</b>。</li>
     *   <li>与 {@code virtualThreadExecutor} 的 100k 限流并存：100k 防 OOM 上限，permits 防连接池过订阅。</li>
     * </ul>
     * </p>
     * <p>
     * 约束：{@code dbFanoutPermits} 必须 &lt; Druid {@code max-active}(当前 30)，并为 XNIO 主请求线程留余量
     * （默认 12，留约 18 给主线程）。可通过配置项 {@code zhiflow.db-fanout.permits} 调整，无需改码。
     * </p>
     * <p>
     * 租户传播：底层委托 {@code virtualThreadExecutor} 为每个任务新建虚拟线程，
     * {@code TransmittableThreadLocal}/{@code InheritableThreadLocal}（如 {@code TenantContextHolder}）
     * 在线程创建时被继承，故多租户 search_path 传播语义不变。
     * </p>
     *
     * @param virtualThreadExecutor 底层虚拟线程执行器（负责真正起线程，保持上下文继承）
     * @param dbFanoutPermits       连接预算（同时借用 DB 连接的 fan-out 任务上限）
     * @return 有界连接预算执行器
     */
    @Bean("dbFanoutExecutor")
    public ExecutorService dbFanoutExecutor(
            @Qualifier("virtualThreadExecutor") ExecutorService virtualThreadExecutor,
            @Value("${zhiflow.db-fanout.permits:12}") int dbFanoutPermits) {

        Semaphore budget = new Semaphore(dbFanoutPermits);
        log.info("dbFanoutExecutor initialized with permits={}", dbFanoutPermits);

        return new ExecutorService() {
            @Override
            public void execute(Runnable task) {
                // 委托底层虚拟线程执行器起线程：acquire/release 都在派生的虚拟线程内部，
                // 不阻塞提交线程；继承 TransmittableThreadLocal（租户上下文）不变。
                virtualThreadExecutor.execute(wrapRunnable(task));
            }

            @Override
            public void shutdown() {
                virtualThreadExecutor.shutdown();
            }

            @Override
            public java.util.List<Runnable> shutdownNow() {
                return virtualThreadExecutor.shutdownNow();
            }

            @Override
            public boolean isShutdown() {
                return virtualThreadExecutor.isShutdown();
            }

            @Override
            public boolean isTerminated() {
                return virtualThreadExecutor.isTerminated();
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return virtualThreadExecutor.awaitTermination(timeout, unit);
            }

            @Override
            public <T> Future<T> submit(Callable<T> task) {
                return virtualThreadExecutor.submit(wrapCallable(task));
            }

            @Override
            public <T> Future<T> submit(Runnable task, T result) {
                return virtualThreadExecutor.submit(wrapRunnable(task), result);
            }

            @Override
            public Future<?> submit(Runnable task) {
                return virtualThreadExecutor.submit(wrapRunnable(task));
            }

            @Override
            public <T> java.util.List<Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> tasks)
                    throws InterruptedException {
                return virtualThreadExecutor.invokeAll(tasks.stream()
                        .map(this::wrapCallable)
                        .toList());
            }

            @Override
            public <T> java.util.List<Future<T>> invokeAll(
                    java.util.Collection<? extends Callable<T>> tasks,
                    long timeout,
                    TimeUnit unit) throws InterruptedException {
                return virtualThreadExecutor.invokeAll(
                        tasks.stream().map(this::wrapCallable).toList(),
                        timeout,
                        unit);
            }

            @Override
            public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks)
                    throws InterruptedException, ExecutionException {
                return virtualThreadExecutor.invokeAny(tasks.stream()
                        .map(this::wrapCallable)
                        .toList());
            }

            @Override
            public <T> T invokeAny(
                    java.util.Collection<? extends Callable<T>> tasks,
                    long timeout,
                    TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                return virtualThreadExecutor.invokeAny(
                        tasks.stream().map(this::wrapCallable).toList(),
                        timeout,
                        unit);
            }

            /**
             * 获取连接预算许可（可中断）。中断时恢复中断标志并以非检查异常包装抛出，
             * 保持 CompletableFuture 的异常语义（异常会落到 future 上）。
             */
            private void acquireBudget() {
                try {
                    budget.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RejectedExecutionException("dbFanoutExecutor interrupted while acquiring connection budget", e);
                }
            }

            private Runnable wrapRunnable(Runnable task) {
                return () -> {
                    acquireBudget();
                    try {
                        task.run();
                    } finally {
                        budget.release();
                    }
                };
            }

            private <T> Callable<T> wrapCallable(Callable<T> task) {
                return () -> {
                    acquireBudget();
                    try {
                        return task.call();
                    } finally {
                        budget.release();
                    }
                };
            }
        };
    }


}
