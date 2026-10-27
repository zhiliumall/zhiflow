package com.zhiflow.mall.common.trade.payexception;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.zhiflow.mall.common.entity.PayException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

/**
 * 差错池入池（v1.2 第六章）。
 *
 * <p><b>独立事务（REQUIRES_NEW）</b>：入池记录的是「钱已经到了」这个事实，不能因为外层业务事务回滚而一起消失——
 * 典型场景是回调处理在入池之后因别的原因抛异常，外层回滚，但钱确实已被渠道确认。
 *
 * <p><b>不抛异常</b>：返回 {@link Result}，由调用方决定入池失败时怎么办（回调侧回 503 让渠道重推，
 * MQ 侧抛异常让消息重投，任务侧计为单笔失败）。「入池失败」时这笔钱的事实只剩日志，所以一律打 ERROR 并计数。
 *
 * <p>指标（构造期预注册，读数 0 = 确实没发生，查不到 = 埋点坏了，同 {@code OrderNoMetrics} 口径）：
 * <ul>
 *   <li>{@code pay_exception_recorded_total{type,group}}：新入池笔数（不入池的类型同一笔在 {@link #LOGGED_DEDUPE_WINDOW}
 *   内只计一次，见 {@link PayExceptionLoggedDedupe}），告警规则按它触发；</li>
 *   <li>{@code pay_exception_record_failed_total{type}}：入池失败（写库异常）。</li>
 * </ul>
 */
@Slf4j
public class PayExceptionRecorder {

	public enum Result {
		/** 新入池 */
		RECORDED,
		/** 同一笔钱此前已入池（dedupe_key 冲突），本次无需再入 */
		ALREADY_RECORDED,
		/** 写库失败——这笔钱的事实只剩日志，调用方必须让上游重试 */
		FAILED,
		/** 不进表的类型（差错池最小版）：已打 ERROR 日志并计数告警，事实在回调留痕 / 退款流水 / 订单本身 */
		LOGGED;

		/** 事实已有落点（新入池或此前已入池）。 */
		public boolean persisted() {
			return this != FAILED;
		}
	}

	private static final String M_RECORDED = "pay_exception_recorded_total";

	private static final String M_FAILED = "pay_exception_record_failed_total";

	/**
	 * 不进表的类型同一笔多久再报一次。巡检 / 轮询每轮都会重新发现同一批存量单；一周提醒一次，
	 * 既不刷屏，也不会让一直没人处理的单从告警里彻底消失（巡检类另有 trade_inspection_violations 常驻告警）。
	 */
	public static final Duration LOGGED_DEDUPE_WINDOW = Duration.ofDays(7);

	private final PayExceptionStore store;

	private final IdentifierGenerator idGenerator;

	private final TransactionTemplate requiresNew;

	private final List<PayExceptionAlertSink> alertSinks;

	private final MeterRegistry meterRegistry;

	private final PayExceptionLoggedDedupe loggedDedupe;

	/**
	 * @param transactionManager 可为 null（单测或无数据源场景），此时不开独立事务
	 * @param meterRegistry      可为 null，缺失时不计数
	 */
	public PayExceptionRecorder(PayExceptionStore store, IdentifierGenerator idGenerator,
			PlatformTransactionManager transactionManager, List<PayExceptionAlertSink> alertSinks,
			MeterRegistry meterRegistry) {
		this(store, idGenerator, transactionManager, alertSinks, meterRegistry,
				PayExceptionLoggedDedupe.inMemory(LOGGED_DEDUPE_WINDOW, System::currentTimeMillis));
	}

	/**
	 * @param loggedDedupe 不进表类型的去重；实际部署接 Redis 版（跨实例、跨重启）
	 */
	public PayExceptionRecorder(PayExceptionStore store, IdentifierGenerator idGenerator,
			PlatformTransactionManager transactionManager, List<PayExceptionAlertSink> alertSinks,
			MeterRegistry meterRegistry, PayExceptionLoggedDedupe loggedDedupe) {
		this.store = store;
		this.loggedDedupe = loggedDedupe;
		this.idGenerator = idGenerator;
		if (transactionManager != null) {
			TransactionTemplate tt = new TransactionTemplate(transactionManager);
			tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
			this.requiresNew = tt;
		}
		else {
			this.requiresNew = null;
		}
		this.alertSinks = alertSinks == null ? List.of() : List.copyOf(alertSinks);
		this.meterRegistry = meterRegistry;
		if (meterRegistry != null) {
			for (PayExceptionType t : PayExceptionType.values()) {
				recordedCounter(t);
				failedCounter(t);
			}
		}
	}

	public Result record(PayExceptionDraft d) {
		if (!d.type().pooled()) {
			// 差错池最小版（2026-09-26）：不进表，但绝不静默——ERROR 日志带齐定位信息 + 与入池同一个计数器（告警规则不变）。
			// 同一笔窗口内只报一次：巡检 / 轮询每轮都会重新发现同一批存量单，不去重就每轮一条严重级告警。
			if (!firstSeen(d.dedupeKey())) {
				log.debug("【资金差错·不入池】窗口内已报过，跳过 type={} dedupe={}", d.type(), d.dedupeKey());
				return Result.LOGGED;
			}
			log.error("【资金差错·不入池】type={} dedupe={} orderNo={} orderId={} payOrderId={} channel={} txn={} "
							+ "amountMinor={} {} callbackLog={} detail={}",
					d.type(), d.dedupeKey(), d.orderNo(), d.orderId(), d.payOrderId(), d.channel(),
					d.channelTxnId(), d.amountMinor(), d.currency(), d.callbackLogId(), d.detail());
			if (meterRegistry != null) {
				recordedCounter(d.type()).increment();
			}
			return Result.LOGGED;
		}
		PayException row = toRow(d);
		int rows;
		try {
			rows = requiresNew == null ? store.insertIfAbsent(row)
					: requiresNew.execute(s -> store.insertIfAbsent(row));
		}
		catch (Exception e) {
			log.error("【差错池】入池失败——这笔钱的事实只剩本条日志，需人工处理 type={} dedupe={} orderNo={} "
							+ "channel={} txn={} amountMinor={} {} detail={}",
					d.type(), row.getDedupeKey(), d.orderNo(), d.channel(), d.channelTxnId(),
					d.amountMinor(), d.currency(), d.detail(), e);
			if (meterRegistry != null) {
				failedCounter(d.type()).increment();
			}
			return Result.FAILED;
		}
		if (rows == 0) {
			log.info("【差错池】同一笔已在池中，跳过 type={} dedupe={}", d.type(), row.getDedupeKey());
			return Result.ALREADY_RECORDED;
		}
		log.error("【差错池】入池 type={} id={} orderNo={} orderId={} payOrderId={} channel={} txn={} amountMinor={} {} detail={}",
				d.type(), row.getId(), d.orderNo(), d.orderId(), d.payOrderId(), d.channel(), d.channelTxnId(),
				d.amountMinor(), d.currency(), d.detail());
		if (meterRegistry != null) {
			recordedCounter(d.type()).increment();
		}
		for (PayExceptionAlertSink sink : alertSinks) {
			try {
				sink.onRecorded(row);
			}
			catch (Exception e) {
				log.warn("【差错池】告警出口异常（入池已成功，不回滚）sink={} id={}", sink.getClass().getSimpleName(),
						row.getId(), e);
			}
		}
		return Result.RECORDED;
	}

	private boolean firstSeen(String dedupeKey) {
		try {
			return loggedDedupe.firstSeen(dedupeKey);
		}
		catch (RuntimeException e) {
			log.warn("【资金差错·不入池】去重不可用，按首次上报 dedupe={}", dedupeKey, e);
			return true;
		}
	}

	PayException toRow(PayExceptionDraft d) {
		PayException row = new PayException();
		row.setId(String.valueOf(idGenerator.nextId(row)));
		row.setType(d.type().name());
		row.setStatus(PayExceptionStatus.OPEN.name());
		row.setOrderId(d.orderId());
		row.setOrderNo(d.orderNo());
		row.setPayOrderId(d.payOrderId());
		row.setChannel(d.channel());
		row.setChannelTxnId(d.channelTxnId());
		row.setAmountMinor(d.amountMinor());
		row.setCurrency(d.currency());
		row.setCallbackLogId(d.callbackLogId());
		row.setDedupeKey(d.dedupeKey());
		row.setDetail(d.detail() == null ? null : truncate(d.detail(), 1000));
		row.setTenantId(d.tenantId());
		return row;
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}

	private Counter recordedCounter(PayExceptionType t) {
		return Counter.builder(M_RECORDED)
				.description("差错池新入池笔数")
				.tag("type", t.name())
				.register(meterRegistry);
	}

	private Counter failedCounter(PayExceptionType t) {
		return Counter.builder(M_FAILED)
				.description("差错池入池失败（写库异常），事实只剩日志")
				.tag("type", t.name())
				.register(meterRegistry);
	}
}
