package com.zhiflow.mall.api.payment;

import com.zhiflow.mall.common.observability.BizMetrics;
import com.zhiflow.mall.common.orderno.OrderNoGenerator;
import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.api.mapper.PayCallbackLogMapper;
import com.zhiflow.mall.api.payment.dto.CallbackResult;
import com.zhiflow.mall.api.payment.dto.PaymentContext;
import com.zhiflow.mall.api.payment.dto.PaymentIntent;
import com.zhiflow.mall.api.payment.dto.PaymentQueryResult;
import com.zhiflow.mall.api.payment.dto.RawCallback;
import com.zhiflow.mall.common.entity.PayCallbackLog;
import com.zhiflow.mall.common.entity.PayOrder;
import com.zhiflow.mall.common.enums.PayBizTypeEnum;
import com.zhiflow.mall.common.trade.payexception.PayExceptionDraft;
import com.zhiflow.mall.common.trade.payexception.PayExceptionRecorder;
import com.zhiflow.mall.common.trade.payexception.PayExceptionType;
import com.zhiflow.mall.common.trade.statemachine.ConcurrentTransitionException;
import com.zhiflow.mall.common.trade.statemachine.LockRunner;
import com.zhiflow.mall.common.trade.statemachine.Transition;
import com.zhiflow.mall.common.trade.statemachine.TxRunner;
import com.zhiflow.mall.common.trade.statemachine.order.OrderStateMachine;
import com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderEvent;
import com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderState;
import com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderStateMachine;
import com.zhiflow.mall.common.trade.fx.CurrencyCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 支付编排层 —— 业务方唯一入口。
 *
 * <p>业务方只跟本类说话，不需要知道自己在跟哪条渠道打交道。
 *
 * <h2>两条线的铁律</h2>
 * <table border="1">
 *   <caption>入账权威</caption>
 *   <tr><th>管钱的线（渠道回调）</th><th>只管看的线（浏览器跳回）</th></tr>
 *   <tr>
 *     <td>渠道服务器直连，带签名；验签 + 四项核对 + 回查通过才置已付。<b>唯一能改状态的线</b></td>
 *     <td>仅展示，<b>绝不入账</b>。状态一律以本地支付单为准</td>
 *   </tr>
 * </table>
 * 若拿浏览器那条线入账，任何人改一下地址栏就能把订单刷成已付、白拿货。
 */
@Slf4j
@Service
public class PaymentService {

    private final PaymentChannelRegistry channelRegistry;

    private final PayOrderService payOrderService;

    private final PayCallbackLogMapper callbackLogMapper;

    private final PayNoGenerator payNoGenerator;

    private final OrderNoGenerator orderNoGenerator;

    /** 持订单锁（{@code lock:order:{bizId}}）确认收款的业务类型。 */
    static final java.util.Set<String> ORDER_LOCKED_BIZ_TYPES = java.util.Set.of(PayBizTypeEnum.ORDER.getValue());

    /** 按 bizType 索引的入账处理器。本阶段可以为空——那时回调会入账支付单但告警业务未推进。 */
    private final Map<String, PaymentSettlementHandler> settlementHandlers;

    /** 支付状态机 v1.2：业务单锁（订单 = lock:order:{id}，与订单状态机同键），让 P4 / P9 的「同业务单无其他 PAID」判定不被并发穿透。 */
    private final LockRunner bizLockRunner;

    /** 事务模板：渠道 HTTP（回查 / 关单）一律在事务之外，事务只包本地写。 */
    private final TxRunner txRunner;

    /** 差错池：重复付款（P9）、渠道侧重复扣款、金额币种不符。 */
    private final PayExceptionRecorder payExceptionRecorder;

    public PaymentService(PaymentChannelRegistry channelRegistry,
                          PayOrderService payOrderService,
                          PayCallbackLogMapper callbackLogMapper,
                          PayNoGenerator payNoGenerator,
                          OrderNoGenerator orderNoGenerator,
                          List<PaymentSettlementHandler> handlers,
                          LockRunner orderLockRunner,
                          TxRunner orderTxRunner,
                          PayExceptionRecorder payExceptionRecorder) {
        this.channelRegistry = channelRegistry;
        this.payOrderService = payOrderService;
        this.callbackLogMapper = callbackLogMapper;
        this.payNoGenerator = payNoGenerator;
        this.orderNoGenerator = orderNoGenerator;
        this.settlementHandlers = handlers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        PaymentSettlementHandler::bizType, Function.identity()));
        this.bizLockRunner = orderLockRunner;
        this.txRunner = orderTxRunner;
        this.payExceptionRecorder = payExceptionRecorder;
        log.info("支付编排层就绪：渠道 {}，入账处理器 {}",
                channelRegistry.registeredCodes(), settlementHandlers.keySet());
    }

    // ────────────────────────────── 发起支付 ──────────────────────────────

    /**
     * 发起支付：落支付单 → 交给渠道 → 返回前端下一步动作。
     *
     * <p><b>金额必须是服务端算好的</b>，本方法不接受也不校正客户端传来的金额。
     */
    public PaymentIntent createPayment(PaymentContext ctx) {
        if (ctx.getAmount() == null || ctx.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("支付金额非法：" + ctx.getAmount());
        }
        PaymentChannel channel = channelRegistry.require(ctx.getChannelCode());
        // 出示币种由渠道决定，且必须在建支付单之前定下来：
        // 支付单 / 收银台表单 / 回调验签三处共用 pay_order.curr_code，写错一处就整条链断。
        ctx.setCurrCode(channel.presentmentCurrency(ctx));
        PayOrder payOrder = payOrderService.createOrReuse(ctx);

        PaymentIntent intent = channel.createPayment(ctx, payOrder);
        payOrderService.markPaying(payOrder.getPayNo());
        return intent;
    }

    /**
     * 发起支付并包装成前端认识的形状。
     *
     * <p>H5 现有的支付调起逻辑只认 {@code next_action}
     * （H5 前端的支付工具函数：{@code next_action.data} 拉起 SDK、
     * {@code next_action.type === 'redirect'} 直接 {@code location.href}）。
     * 这里把 {@link PaymentIntent} 翻译过去，于是<b>前端不需要为新渠道写任何跳转代码</b>。
     *
     * <p>{@link PaymentIntent.Type#FORM_POST} 也映射成 {@code redirect}：
     * 渠道要求浏览器 form POST，但那件事由我方中转页承接，对前端就是一次普通跳转。
     */
    public R<?> createPaymentAsR(PaymentContext ctx) {
        try {
            PaymentIntent intent = createPayment(ctx);
            Map<String, Object> nextAction = new LinkedHashMap<>();
            switch (intent.getType()) {
                case SDK -> nextAction.put("data", intent.getPayParams());
                case REDIRECT, FORM_POST -> {
                    nextAction.put("type", "redirect");
                    nextAction.put("url", intent.getRedirectUrl());
                }
                case DONE -> nextAction.put("type", "done");
                default -> {
                    return R.failed("支付方式暂不可用");
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("payNo", intent.getPayNo());
            data.put("next_action", nextAction);
            // 支付意图对象无 code/ok 字段，补 ok=true 供 H5 的成功判定
            data.put("ok", true);
            return R.ok(data);
        }
        catch (IllegalArgumentException | IllegalStateException e) {
            // 配置缺失、渠道未注册、金额非法这类**可预期**的失败：给用户一句人话，
            // 不把内部细节（商户号、地址、密钥字段名）漏到响应里
            log.error("发起支付失败 bizType={} bizId={} channel={}：{}",
                    ctx.getBizType(), ctx.getBizId(), ctx.getChannelCode(), e.getMessage());
            return R.failed("支付发起失败，请稍后重试或更换支付方式");
        }
    }

    // ────────────────────────────── 回调入账 ──────────────────────────────

    /**
     * 处理渠道回调 —— 入账主线（回调九步，新框架版）。
     *
     * <p>顺序：① 验签 → ② 留痕（被拒过的可重新认领）→ ④ 按事件分流 → ⑥ 定位支付单 → 报文金额币种核对 →
     * ⑦ <b>锁外</b>回查渠道 → ⑧ 回查金额核对 → ⑨⑩ 业务单锁内由支付单状态机选 P4 / P7 / P9 并跳转，同事务入账。
     * <b>本方法不开事务、不持锁做任何渠道 HTTP</b>（末段）。
     *
     * @return 要回给渠道的应答体。<b>回错会让渠道判定投递失败并反复重推</b>
     */
    public CallbackOutcome handleCallback(RawCallback raw) {
        // pay_callback_total{tenant,channel,outcome} + pay_callback_latency_seconds{channel}
        long start = System.nanoTime();
        // 渠道标签只收已注册渠道码；公网乱填的记 unknown，且其异常算 rejected（不是我们的系统错误）
        String channelTag = BizMetrics.callbackChannel(raw.getChannelCode(), channelRegistry::supports);
        boolean knownChannel = !BizMetrics.CHANNEL_UNKNOWN.equals(channelTag);
        try {
            CallbackOutcome outcome = doHandleCallback(raw);
            BizMetrics.payCallback(raw.getTenantId(), channelTag,
                    outcome.accepted() ? BizMetrics.CALLBACK_OK : BizMetrics.CALLBACK_REJECTED, System.nanoTime() - start);
            return outcome;
        }
        catch (RuntimeException | Error e) {
            BizMetrics.payCallback(raw.getTenantId(), channelTag,
                    knownChannel ? BizMetrics.CALLBACK_ERROR : BizMetrics.CALLBACK_REJECTED, System.nanoTime() - start);
            throw e;
        }
    }

    private CallbackOutcome doHandleCallback(RawCallback raw) {
        PaymentChannel channel = channelRegistry.require(raw.getChannelCode());
        CallbackResult result = channel.parseCallback(raw);

        // ── ① 验签 ──
        if (!result.isSignValid()) {
            log.error("支付回调验签失败并已丢弃：channel={} tenantId={} sourceIp={} idem={}",
                    raw.getChannelCode(), raw.getTenantId(), raw.getSourceIp(),
                    result.getIdempotentKey());
            // 阶段 0 · 0.9：验签失败的报文里的幂等键是对方自己填的，不可信。用它留痕会抢先占住
            // 真实回调的幂等位（伪造一条同 PayRef 的报文就能让真回调被当重复吞掉）——换成不会冲突的键。
            writeRejectedSignatureLog(raw, result);
            return CallbackOutcome.rejected();
        }

        // ── ② 留痕 + 幂等第一道闸 ──
        boolean firstTime = writeLog(raw, result, "1", "RECEIVED", null) > 0;
        // 只有 ACCEPTED / ACCEPTED_DUP 占幂等位——被拒过、被忽略过（失败 / 处理中）、
        // 处理到一半没落定的同一条回调，渠道重推时都要重新处理
        if (!firstTime && StrUtil.isNotBlank(result.getIdempotentKey())
                && callbackLogMapper.reclaimNotAccepted(raw.getChannelCode(), result.getIdempotentKey()) > 0) {
            log.info("支付回调此前未被接受，本次重推重新处理：channel={} payNo={} idem={}",
                    raw.getChannelCode(), result.getPayNo(), result.getIdempotentKey());
            firstTime = true;
        }
        if (!firstTime) {
            log.info("支付回调重复投递，已忽略：channel={} payNo={} idem={}",
                    raw.getChannelCode(), result.getPayNo(), result.getIdempotentKey());
            return CallbackOutcome.ack(result);
        }

        // ── ④ 分流 ──
        if (result.getEvent() != CallbackResult.Event.PAID) {
            log.info("支付回调非成功事件，不入账：channel={} payNo={} event={} status={}",
                    raw.getChannelCode(), result.getPayNo(), result.getEvent(),
                    result.getChannelStatus());
            // 失败 / 处理中只是「去查」的触发，不改状态（P5 由补单任务查到明确失败才走）；IGNORED 不占幂等位，
            // 同一流水号随后的成功回调照常处理（④）
            updateLogStatus(raw, result, "IGNORED", "非成功事件:" + result.getEvent());
            return CallbackOutcome.ack(result);
        }

        // 统一入口的报文必须带金额（微信、支付宝等渠道恒带）；缺金额按核对不通过拒绝，不放行
        if (result.getAmount() == null) {
            markLogRejected(raw, result, "报文缺金额");
            return CallbackOutcome.rejected();
        }
        ConfirmResult c = confirmPayment(result.getPayNo(), result.getChannelTxnId(), result.getAmount(),
                result.getCurrCode(), callbackLogId(raw, result));
        switch (c.outcome()) {
            case ACCEPTED -> markLogAccepted(raw, result);
            case DUPLICATE -> markLogDuplicate(raw, result);
            case DUP_CHARGE -> updateLogStatus(raw, result, "REJECTED", "DUP_CHARGE");
            default -> markLogRejected(raw, result, StrUtil.maxLength(c.detail(), 250));
        }
        return c.ack() ? CallbackOutcome.ack(result) : CallbackOutcome.rejected();
    }

    /** 确认收款的结论（统一回调、状态探针、补单任务共用）。 */
    public enum ConfirmOutcome {
        /** P4 / P7 放行并已入账（或 P9 放行并入差错池） */
        ACCEPTED,
        /** 同一流水号重复确认 */
        DUPLICATE,
        /** 已付支付单收到不同流水号：渠道侧重复扣款，已入差错池（回 200） */
        DUP_CHARGE,
        /** 金额 / 币种不符，已入差错池（回 200） */
        MISMATCH,
        /** 查无此支付单 */
        NOT_FOUND,
        /** 回查查不通（UNKNOWN 不是未付） */
        QUERY_UNKNOWN,
        /** 回查未确认已付 */
        QUERY_NOT_PAID,
        /** 支付单状态机拒绝 */
        REJECTED,
        /** 差错池写库失败（事实不能只剩日志：不回成功，让渠道重推） */
        POOL_FAILED,
        /** 条件更新两次落空 */
        CONFLICT
    }

    public record ConfirmResult(ConfirmOutcome outcome, PayOrderEvent event, String detail) {

        /** 可以回渠道「已收到」的结论（其余让渠道重推 / 下一轮再查）。 */
        public boolean ack() {
            return outcome == ConfirmOutcome.ACCEPTED || outcome == ConfirmOutcome.DUPLICATE
                    || outcome == ConfirmOutcome.DUP_CHARGE || outcome == ConfirmOutcome.MISMATCH;
        }

        static ConfirmResult of(ConfirmOutcome o, String detail) {
            return new ConfirmResult(o, null, detail);
        }
    }

    /**
     * 确认收款（第⑥–⑩步，已验签之后的部分）。<b>不开事务、不持锁做渠道 HTTP</b>：
     * ⑥ 定位支付单 → 报文金额币种核对（报文有才比）→ ⑦ 锁外回查 → ⑧ 回查金额币种核对 →
     * ⑨⑩ 业务单锁 → 事务 → 支付单状态机选 P4 / P7 / P9 → 同事务入账 / 入差错池。
     *
     * @param notifiedAmount   报文金额（出示币种，元）；null = 报文没给，不比（回查必比）
     * @param notifiedCurrency 报文币种；null = 不比
     * @param callbackLogId    回调留痕键 / 来源标记，写进 pay_order_state_log
     */
    public ConfirmResult confirmPayment(String payNo, String notifiedTxnId, BigDecimal notifiedAmount,
                                        String notifiedCurrency, String callbackLogId) {
        PayOrder payOrder = StrUtil.isBlank(payNo) ? null : payOrderService.getByPayNo(payNo);
        if (payOrder == null) {
            log.error("确认收款：查无此支付单 payNo={}", payNo);
            return ConfirmResult.of(ConfirmOutcome.NOT_FOUND, "查无此支付单");
        }
        if (notifiedAmount != null) {
            String mismatch = verify(payOrder, notifiedAmount, notifiedCurrency);
            if (mismatch != null) {
                return amountMismatch(payOrder, notifiedTxnId, callbackLogId, "报文" + mismatch);
            }
        }

        // ── ⑨ 提前：支付单已是收款类状态且流水号相同 → 这笔钱早已入账，本次是重复（ACCEPTED_DUP），不再回查 ──
        // 已收款的状态不会再回到未付（明确禁止），锁外读到就是定论。放到回查之前是因为：重复回调没必要再跨网络回查一次。
        if (StrUtil.isNotBlank(notifiedTxnId) && notifiedTxnId.equals(payOrder.getChannelTxnId())
                && PAID_LIKE.contains(payOrder.getStatus())) {
            log.info("确认收款：支付单 payNo={} 已是 {}（同一流水号），按重复处理", payNo, payOrder.getStatus());
            return new ConfirmResult(ConfirmOutcome.DUPLICATE, PayOrderEvent.PAY_CONFIRMED, null);
        }

        // ── ⑦ 锁外回查（防伪二锁）──
        PaymentQueryResult verified;
        try {
            verified = channelRegistry.require(payOrder.getChannelCode()).query(payOrder);
        }
        catch (Exception e) {
            log.error("确认收款回查异常 payNo={}", payNo, e);
            verified = PaymentQueryResult.unknown(e.getMessage());
        }
        if (verified.isUnknown()) {
            log.error("确认收款回查查不通，暂不入账：payNo={} 详情={}", payNo, verified.getRawMessage());
            return ConfirmResult.of(ConfirmOutcome.QUERY_UNKNOWN, "回查查不通:" + verified.getRawMessage());
        }
        if (!verified.isPaid()) {
            log.error("确认收款回查未确认已付，暂不入账：payNo={} 回查状态={} 详情={}",
                    payNo, verified.getState(), verified.getRawMessage());
            return ConfirmResult.of(ConfirmOutcome.QUERY_NOT_PAID, "回查未确认:" + verified.getState());
        }
        if (verified.getPaidAmount() != null) {
            String queried = verify(payOrder, verified.getPaidAmount(), verified.getCurrCode());
            if (queried != null) {
                return amountMismatch(payOrder, notifiedTxnId, callbackLogId, "回查" + queried);
            }
        }
        else if (notifiedAmount == null) {
            // 报文与回查都拿不到金额：不拦（渠道查单字段名待首笔真单核对，IMPLEMENTATION D3 / DECISIONS Q14），只计数告警
            io.micrometer.core.instrument.Metrics.counter("payment_amount_unverified_total",
                    "channel", String.valueOf(payOrder.getChannelCode())).increment();
            log.warn("确认收款：报文与回查均无金额，按旧行为放行 payNo={}", payNo);
        }
        String channelTxnId = StrUtil.blankToDefault(notifiedTxnId, verified.getChannelTxnId());

        // ── ⑨⑩ 业务单锁 → 事务 → 状态机 P4 / P7 / P9 → 同事务入账 ──
        // 锁订单（lock:order:{orderId}，与订单状态机同键）：同一订单的两笔回调同时到时在这里串行，
        // 「同业务单无其他 PAID」判定不会被并发穿透
        String lockKey = ORDER_LOCKED_BIZ_TYPES.contains(payOrder.getBizType())
                ? OrderStateMachine.lockKey(payOrder.getBizId())
                : "paybiz:" + payOrder.getBizType() + ":" + payOrder.getBizId();
        PaymentQueryResult finalVerified = verified;
        for (int attempt = 1; ; attempt++) {
            try {
                return bizLockRunner.withLock(lockKey, OrderStateMachine.LOCK_WAIT,
                        () -> txRunner.inTx(() -> applyConfirmed(payOrder, channelTxnId, finalVerified, callbackLogId)));
            }
            catch (ConcurrentTransitionException e) {
                if (attempt >= 2) {
                    log.error("确认收款条件更新两次落空 payNo={}", payNo, e);
                    return ConfirmResult.of(ConfirmOutcome.CONFLICT, "并发冲突");
                }
                log.warn("确认收款条件更新落空，重读重判一次 payNo={}", payNo);
            }
            catch (PayExceptionUnrecorded e) {
                return ConfirmResult.of(ConfirmOutcome.POOL_FAILED, e.getMessage());
            }
        }
    }

    /** 锁内、事务内：选事件跳转；P4 / P7 同事务入账，P9 入差错池，已付单不同流水号入差错池 DUP_CHARGE。 */
    private ConfirmResult applyConfirmed(PayOrder snapshot, String channelTxnId, PaymentQueryResult verified,
                                         String callbackLogId) {
        PayOrderService.PaidOutcome out = payOrderService.confirmPaid(snapshot, channelTxnId, verified.getPayTime(),
                callbackLogId);
        Transition<PayOrderState> t = out.transition();
        if (t.isNoop()) {
            log.info("支付单 payNo={} 已入过账（同一流水号），本次按重复处理", snapshot.getPayNo());
            return new ConfirmResult(ConfirmOutcome.DUPLICATE, out.event(), null);
        }
        if (t.isRejected()) {
            if (t.reason() != null && t.reason().startsWith(PayOrderStateMachine.DUP_CHARGE)) {
                recordOrThrow(draft(PayExceptionType.DUP_CHARGE, PayExceptionDraft.scopeOfTxn(snapshot.getChannelCode(),
                        channelTxnId), snapshot, channelTxnId, callbackLogId, t.reason()));
                return new ConfirmResult(ConfirmOutcome.DUP_CHARGE, out.event(), t.reason());
            }
            log.error("确认收款被支付单状态机拒绝 payNo={} 事件={} 原因={}", snapshot.getPayNo(), out.event(), t.reason());
            return new ConfirmResult(ConfirmOutcome.REJECTED, out.event(), "状态机拒绝:" + t.reason());
        }
        PayOrder paid = payOrderService.getByPayNo(snapshot.getPayNo());
        if (out.event() == PayOrderEvent.DUPLICATE_PAY_CONFIRMED) {
            // P9：不发支付成功、不推进订单；入差错池 DUP_PAY（自动退款在阶段 2）
            recordOrThrow(draft(PayExceptionType.DUP_PAY, PayExceptionDraft.scopeOfPayOrder(paid.getId()), paid,
                    channelTxnId, callbackLogId, "同业务单已有另一张已付支付单，本单为重复付款"));
        }
        else {
            // P4 / P7：交业务侧入账。P7 时订单若已不是待付款，订单侧会把这笔钱写差错池 LATE_PAID
            settle(paid);
        }
        log.info("支付入账完成：payNo={} channel={} txn={} amount={} 事件={}",
                paid.getPayNo(), paid.getChannelCode(), channelTxnId, paid.getAmount(), out.event());
        return new ConfirmResult(ConfirmOutcome.ACCEPTED, out.event(), null);
    }

    /** ⑧ 金额 / 币种不符：差错池 AMOUNT_MISMATCH，回 200（重推无意义）；入池失败则不回成功。 */
    private ConfirmResult amountMismatch(PayOrder payOrder, String channelTxnId, String callbackLogId, String why) {
        log.error("确认收款核对不通过：payNo={} 原因={}", payOrder.getPayNo(), why);
        PayExceptionRecorder.Result r = payExceptionRecorder.record(draft(PayExceptionType.AMOUNT_MISMATCH,
                PayExceptionDraft.scopeOfPayOrder(payOrder.getId()), payOrder, channelTxnId, callbackLogId, why));
        return ConfirmResult.of(r.persisted() ? ConfirmOutcome.MISMATCH : ConfirmOutcome.POOL_FAILED, why);
    }

    private void recordOrThrow(PayExceptionDraft d) {
        if (!payExceptionRecorder.record(d).persisted()) {
            throw new PayExceptionUnrecorded("差错入池失败：" + d.type());
        }
    }

    private static PayExceptionDraft draft(PayExceptionType type, String scope, PayOrder p, String txn,
                                           String callbackLogId, String detail) {
        return PayExceptionDraft.builder()
                .type(type)
                .dedupeScope(scope)
                .tenantId(p.getTenantId())
                .orderId(p.getBizId())
                .orderNo(p.getOrderNo())
                .payOrderId(p.getId())
                .channel(p.getChannelCode())
                .channelTxnId(txn)
                .amountMinor(PayExceptionDraft.toMinor(p.getAmount()))
                .currency(p.getCurrency() != null ? p.getCurrency() : CurrencyCodes.alpha(p.getCurrCode()))
                .callbackLogId(callbackLogId)
                .detail(StrUtil.maxLength(detail, 500))
                .build();
    }

    /** 入池失败：事务回滚（支付单不动），回调回非成功让渠道重推。 */
    static final class PayExceptionUnrecorded extends RuntimeException {
        PayExceptionUnrecorded(String msg) {
            super(msg);
        }
    }

    private String callbackLogId(RawCallback raw, CallbackResult result) {
        return raw.getChannelCode() + ":" + result.getIdempotentKey();
    }

    /**
     * 金额 / 币种核对。返回 null 表示通过，否则返回不通过的原因。
     *
     * <p>官方文档普遍明确禁止「只凭成功状态码就入账」——必须逐项核对金额与币种，
     * 否则改一个金额字段就能用 1 分钱买走整单。已取消的支付单<b>不在这里拒</b>：v1.2 起它走 P7 / P9，钱要有落点。
     */
    private String verify(PayOrder payOrder, BigDecimal amount, String currCode) {
        if (amount == null || payOrder.getAmount().compareTo(amount) != 0) {
            return String.format("金额不符 本地=%s 渠道=%s", payOrder.getAmount(), amount);
        }
        if (StrUtil.isNotBlank(currCode) && !currCode.equalsIgnoreCase(payOrder.getCurrCode())) {
            return String.format("币种不符 本地=%s 渠道=%s", payOrder.getCurrCode(), currCode);
        }
        return null;
    }

    /** 把业务状态推进交给对应处理器。没有处理器要吼出来——钱到了但业务没动是最危险的状态。 */
    private void settle(PayOrder payOrder) {
        PaymentSettlementHandler handler = settlementHandlers.get(payOrder.getBizType());
        if (handler == null) {
            log.error("支付已入账但找不到业务入账处理器！payNo={} bizType={} 已注册={} "
                            + "——钱已收到而业务状态未推进，需人工介入",
                    payOrder.getPayNo(), payOrder.getBizType(), settlementHandlers.keySet());
            return;
        }
        handler.onPaid(payOrder);
    }

    // ────────────────────────────── 查单 ──────────────────────────────

    /**
     * 向渠道查单。用于取消前复核、对账兜底、浏览器返回兜底。
     *
     * <p>查不通返回 {@code UNKNOWN}，<b>调用方不得把 UNKNOWN 当作未支付</b>——
     * 那会把一笔其实已付的订单取消掉并回滚库存。
     */
    public PaymentQueryResult query(String payNo) {
        PayOrder payOrder = payOrderService.getByPayNo(payNo);
        if (payOrder == null) {
            return PaymentQueryResult.unknown("查无此支付单:" + payNo);
        }
        try {
            return channelRegistry.require(payOrder.getChannelCode()).query(payOrder);
        }
        catch (Exception e) {
            log.error("查单异常 payNo={} channel={}", payNo, payOrder.getChannelCode(), e);
            return PaymentQueryResult.unknown(e.getMessage());
        }
    }

    /** 收到过钱的支付单状态：之后不会回到未付。 */
    private static final java.util.Set<String> PAID_LIKE = java.util.Set.of(PayOrderState.PAID.code());

    // ────────────────────────────── 留痕 ──────────────────────────────

    private int writeLog(RawCallback raw, CallbackResult result,
                         String signValid, String processStatus, String rejectReason) {
        PayCallbackLog entry = new PayCallbackLog();
        entry.setPayNo(result.getPayNo());
        entry.setChannelCode(raw.getChannelCode());
        entry.setIdempotentKey(StrUtil.blankToDefault(
                result.getIdempotentKey(), raw.getChannelCode() + ":" + System.nanoTime()));
        entry.setRawBody(raw.getBody());
        entry.setSignValid(signValid);
        entry.setChannelStatus(result.getChannelStatus());
        entry.setProcessStatus(processStatus);
        entry.setRejectReason(rejectReason);
        entry.setTenantId(raw.getTenantId());
        // id 由 MyBatis-Plus 的 ASSIGN_ID 填不了（走的是注解 SQL），这里显式给
        entry.setId(orderNoGenerator.next());
        return callbackLogMapper.insertIfAbsent(entry);
    }

    /** 验签失败的留痕：幂等键换成不会与真实回调冲突的值，对方声称的键写进拒绝原因以便排查。 */
    private void writeRejectedSignatureLog(RawCallback raw, CallbackResult result) {
        PayCallbackLog entry = new PayCallbackLog();
        entry.setPayNo(result.getPayNo());
        entry.setChannelCode(raw.getChannelCode());
        entry.setId(orderNoGenerator.next());
        // 用本条留痕自己的 id 拼键：全局唯一，多实例下也不会与别的留痕（包括真实回调）撞键
        entry.setIdempotentKey("sig-rejected:" + raw.getChannelCode() + ":" + entry.getId());
        entry.setRawBody(raw.getBody());
        entry.setSignValid("0");
        entry.setChannelStatus(result.getChannelStatus());
        entry.setProcessStatus("REJECTED");
        entry.setRejectReason(StrUtil.maxLength("验签失败，报文声称的幂等键=" + result.getIdempotentKey(), 250));
        entry.setTenantId(raw.getTenantId());
        callbackLogMapper.insertIfAbsent(entry);
    }

    private void markLogAccepted(RawCallback raw, CallbackResult result) {
        updateLogStatus(raw, result, "ACCEPTED", null);
    }

    /** 重复入账：记 ACCEPTED_DUP，但不覆盖同一条回调并发重入时已经记下的 ACCEPTED。 */
    private void markLogDuplicate(RawCallback raw, CallbackResult result) {
        PayCallbackLog patch = new PayCallbackLog();
        patch.setProcessStatus("ACCEPTED_DUP");
        callbackLogMapper.update(patch,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<PayCallbackLog>lambdaUpdate()
                        .eq(PayCallbackLog::getChannelCode, raw.getChannelCode())
                        .eq(PayCallbackLog::getIdempotentKey, result.getIdempotentKey())
                        .ne(PayCallbackLog::getProcessStatus, "ACCEPTED"));
    }

    private void markLogRejected(RawCallback raw, CallbackResult result, String reason) {
        updateLogStatus(raw, result, "REJECTED", reason);
    }

    private void updateLogStatus(RawCallback raw, CallbackResult result,
                                 String processStatus, String reason) {
        PayCallbackLog patch = new PayCallbackLog();
        patch.setProcessStatus(processStatus);
        patch.setRejectReason(reason);
        callbackLogMapper.update(patch,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<PayCallbackLog>lambdaUpdate()
                        .eq(PayCallbackLog::getChannelCode, raw.getChannelCode())
                        .eq(PayCallbackLog::getIdempotentKey, result.getIdempotentKey()));
    }

    /**
     * 回调处理结论。
     *
     * <p>{@code ackBody} 是要原样写回 HTTP 响应体的内容——各渠道约定不同
     * （支付宝要纯文本 {@code success}），回错会让渠道反复重推。
     */
    public record CallbackOutcome(boolean accepted, String ackBody, String contentType) {

        static CallbackOutcome ack(CallbackResult result) {
            return new CallbackOutcome(true,
                    StrUtil.blankToDefault(result.getAckBody(), "success"),
                    StrUtil.blankToDefault(result.getAckContentType(), "text/plain"));
        }

        /** 不回渠道期望的成功应答，让它按自身策略重推，由后续回调或对账收敛。 */
        static CallbackOutcome rejected() {
            return new CallbackOutcome(false, "FAIL", "text/plain");
        }
    }
}
