package com.zhiflow.mall.common.orderno;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.zhiflow.common.core.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 订单号生成的唯一入口。
 *
 * <p>产出 19 位纯数字字符串，位结构与收口前逐字节一致：
 * <pre>
 * order_no = (System.currentTimeMillis() - 1288834974657) &lt;&lt; 22
 *          | dataCenterId(5 bit) &lt;&lt; 17
 *          | workerId(5 bit)     &lt;&lt; 12
 *          | sequence(12 bit)
 * </pre>
 *
 * <h2>为什么走 IdUtil.getSnowflake 而不是 new Snowflake</h2>
 *
 * {@code IdUtil.getSnowflake(long,long)} 的字节码是
 * {@code Singleton.get(Snowflake.class, workerId, dataCenterId)}——按「类 + 参数」缓存。
 * 收口后仓库里仍保留多处直接调 {@code IdUtil.getSnowflake(0,0)} 的代码
 * （资金单据 tradeNo / recordNo 一类，不属订单号，本次刻意不收口）。
 *
 * <p>走 {@code Singleton} 才能与那些残留调用点**共用同一个 Snowflake 实例**：
 * {@code nextId()} 带 {@code synchronized}，序列连续，行为与收口前完全一致。
 * 若改成 {@code new Snowflake(0,0)}，同一 JVM 内会出现两个各自从 sequence=0 起步的
 * 实例，**同毫秒可产出完全相同的号**——那是本次重构凭空引入的新碰撞源。
 *
 * <h2>不是静态工具类</h2>
 *
 * 机器位由 {@link WorkerIdAssigner} 在构造期注入。T2 要在启动期动态分配 workerId，
 * 静态方法拿不到那个值。
 *
 * <p>⚠️ 不属于本类职责（勿混用）：
 * <ul>
 *   <li>{@code order_info.id} 走 MyBatis-Plus {@code @TableId(ASSIGN_ID)}，机器位从 MAC+PID
 *       推导，是**另一套雪花**，与 order_no 互相推不出来，严禁改走本类</li>
 *   <li>实体主键 {@code setId()}、资金单据 {@code tradeNo}/{@code recordNo} 均不是订单号</li>
 * </ul>
 */
public class OrderNoGenerator {

	private static final Logger log = LoggerFactory.getLogger(OrderNoGenerator.class);

	/**
	 * Hutool {@code Snowflake.DEFAULT_TIME_OFFSET}，由 javap 反编译 5.8.44 确认。
	 *
	 * <p>⚠️ <b>这是 Hutool 的内置容忍度，不是本项目定的策略。</b>
	 * 回拨 ≤ 该值时 Hutool 静默复用 {@code lastTimestamp} 继续发号（不抛异常）；
	 * 超过则抛 {@code IllegalStateException}。改这个常量并不会改变 Hutool 的行为，
	 * 它在这里只用于把两段行为分开计数与告警。
	 */
	static final long HUTOOL_TIME_OFFSET_MILLIS = 2000L;

	private final Snowflake snowflake;

	private final long workerId;

	private final long dataCenterId;

	private final OrderNoMetrics metrics;

	private final OrderNoFailureSink failureSink;

	/** 时间源。生产恒为 {@code System::currentTimeMillis}；抽出来只为单测能模拟回拨。 */
	private final LongSupplier clock;

	/** 本进程观察到的最大时刻水位，用于发现系统时钟倒退。 */
	private final AtomicLong lastSeenMillis = new AtomicLong(Long.MIN_VALUE);

	/** 已告警过的回拨水位，用于让同一次回拨只打一条日志（指标仍逐次计）。 */
	private final AtomicLong lastWarnedWatermark = new AtomicLong(Long.MIN_VALUE);

	public OrderNoGenerator(WorkerIdAssigner workerIdAssigner, OrderNoMetrics metrics,
			OrderNoFailureSink failureSink) {
		this(workerIdAssigner, metrics, failureSink, System::currentTimeMillis);
	}

	OrderNoGenerator(WorkerIdAssigner workerIdAssigner, OrderNoMetrics metrics,
			OrderNoFailureSink failureSink, LongSupplier clock) {
		this.workerId = workerIdAssigner.getWorkerId();
		this.dataCenterId = workerIdAssigner.getDataCenterId();
		this.metrics = metrics;
		this.failureSink = failureSink;
		this.clock = clock;
		// 见类注释：必须走 Singleton 缓存，不能 new
		this.snowflake = IdUtil.getSnowflake(this.workerId, this.dataCenterId);
	}

	/**
	 * 生成一个订单号。
	 *
	 * <p>时钟回拨的两段行为都会被观测到（详见 {@link #HUTOOL_TIME_OFFSET_MILLIS}）：
	 * ≤2s 打 WARN 并计数、**发号照常成功**；>2s 时 Hutool 抛 {@code IllegalStateException}，
	 * 这里捕获后打 ERROR、计数、落 Sink，转抛 {@link BizException}——不让裸异常冒到用户面前。
	 *
	 * @return 19 位纯数字字符串
	 */
	public String next() {
		long now = clock.getAsLong();
		long previous = lastSeenMillis.getAndAccumulate(now, Math::max);
		long drift = previous == Long.MIN_VALUE ? 0L : previous - now;

		if (drift > 0) {
			// ⚠️ 水位取 max，所以一次回拨会让**窗口内每一次发号**都算出 drift > 0
			//（时钟从 5000 回到 3000，则 now 追回 5000 之前的每次调用都命中）。
			// 指标刻意仍逐次计数——它衡量的是「回拨影响了多少次发号」，那正是要看的量；
			// 但日志按水位去重，同一次回拨只打一条，否则高峰期两秒就能刷出几千条。
			boolean firstOfThisRollback = lastWarnedWatermark.getAndSet(previous) != previous;
			if (drift <= HUTOOL_TIME_OFFSET_MILLIS) {
				// Hutool 静默复用 lastTimestamp，号仍然发得出来。这段在外部本来完全不可见。
				metrics.clockRollback(OrderNoMetrics.SEVERITY_TOLERATED);
				if (firstOfThisRollback) {
					log.warn("检测到系统时钟回拨 {}ms（在 Hutool 容忍区 {}ms 内，发号继续）。"
							+ "宿主机若用 systemd-timesyncd 的阶跃校时，可能很快出现超容忍区的回拨。"
							+ "本条按回拨水位去重，同一次回拨只打一条；影响次数看 "
							+ "orderno_clock_rollback_total 读数",
							drift, HUTOOL_TIME_OFFSET_MILLIS);
				}
			}
			else {
				metrics.clockRollback(OrderNoMetrics.SEVERITY_EXCEEDED);
				if (firstOfThisRollback) {
					log.error("检测到系统时钟回拨 {}ms，已超出 Hutool 容忍区 {}ms，期间发号将持续失败。"
							+ "本条按回拨水位去重，同一次回拨只打一条",
							drift, HUTOOL_TIME_OFFSET_MILLIS);
				}
			}
		}

		try {
			return snowflake.nextIdStr();
		}
		catch (IllegalStateException e) {
			// Hutool 在回拨 >2s 时抛的就是这个。不能让它裸奔到用户。
			metrics.generateFail(OrderNoMetrics.REASON_CLOCK_ROLLBACK);
			// drift 是「本进程观察到的」回拨幅度，Hutool 用的是它自己记的 lastTimestamp。
			// 二者可能不一致（本进程首次发号、或该 Snowflake 实例被其他调用点先推进过），
			// 此时 drift 会是 0——别把它当成"没回拨却失败了"，判据以 Hutool 抛出为准。
			String observed = drift > 0
					? "本进程观察到回拨 " + drift + "ms"
					: "本进程未观察到回拨（drift=0），以 Hutool 内部 lastTimestamp 为准";
			log.error("订单号生成失败：Hutool 判定时钟回拨超出容忍区 {}ms（{}）。"
					+ "处置：确认宿主机 NTP 状态（建议 chrony makestep 0.1 3，仅启动时步进）",
					HUTOOL_TIME_OFFSET_MILLIS, observed, e);
			failureSink.record(OrderNoMetrics.REASON_CLOCK_ROLLBACK,
					"时钟回拨超出 Hutool 容忍区 " + HUTOOL_TIME_OFFSET_MILLIS + "ms；" + observed, e);
			throw new BizException("系统繁忙，请稍后重试");
		}
	}

	/**
	 * 当前生效的机器位，供 T2 启动期自检与排障日志使用。
	 */
	public long getWorkerId() {
		return workerId;
	}

	public long getDataCenterId() {
		return dataCenterId;
	}
}
