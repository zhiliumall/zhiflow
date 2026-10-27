package com.zhiflow.mall.api.payment;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiflow.mall.api.mapper.PayOrderMapper;
import com.zhiflow.mall.api.payment.dto.PaymentContext;
import com.zhiflow.mall.common.entity.PayOrder;
import com.zhiflow.mall.common.enums.PayBizTypeEnum;
import com.zhiflow.mall.common.trade.statemachine.Operator;
import com.zhiflow.mall.common.trade.statemachine.Transition;
import com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderEvent;
import com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderState;
import com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderStateMachine;
import com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderStateSync;
import com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderTransitionContext;
import com.zhiflow.mall.common.trade.fx.CurrencyCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付单的落库与状态流转。
 *
 * <p>支付状态机 v1.2 阶段 1 起，状态流转与退款额度全部经 {@link PayOrderStateMachine}（锁内重读、条件 UPDATE、
 * pay_order_state_log、outbox 事件）；本类只负责建单 / 复用与「选哪个事件」。此前的原则不变——不读出实体改字段再 {@code updateById}：
 * 后者会把读快照时的全部字段写回，并发下会把别人刚落的更新静默覆盖——
 * 本仓订单侧踩过这个坑（已支付订单被快照里的 {@code is_pay='0'} 改回未支付，用户可二次支付）。
 */
@Slf4j
@Service
public class PayOrderService {

    private final PayOrderMapper payOrderMapper;

    private final PayNoGenerator payNoGenerator;

    /** 支付状态机 v1.2 阶段 1：pay_order.status 与退款额度的唯一写入口。可为 null（只测建单的单测）。 */
    private final PayOrderStateMachine stateMachine;

    public PayOrderService(PayOrderMapper payOrderMapper, PayNoGenerator payNoGenerator) {
        this(payOrderMapper, payNoGenerator, null);
    }

    /** P6 之后的可选渠道关单。setter 注入：只测建单的单测不需要它。 */
    private PayOrderChannelCloser channelCloser;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setChannelCloser(PayOrderChannelCloser channelCloser) {
        this.channelCloser = channelCloser;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PayOrderService(PayOrderMapper payOrderMapper, PayNoGenerator payNoGenerator,
                           PayOrderStateMachine stateMachine) {
        this.payOrderMapper = payOrderMapper;
        this.payNoGenerator = payNoGenerator;
        this.stateMachine = stateMachine;
    }

    /**
     * 取得可用的支付单：同一业务单 + 同一渠道若已有未终态的支付单，<b>复用它</b>，否则新建。
     *
     * <p>复用是必须的，不是优化：用户在收银台反复点「去支付」时若每次都新建支付单，
     * 渠道侧会以「商户订单号已存在」拒绝，或者更糟——产生多个都能付款的支付单。
     *
     * <p>换渠道则是另一个支付单（{@code channelCode} 参与匹配），
     * 这样用户先选微信没付、再改选银行卡时两条线各自独立，对账时也分得清。
     */
    @Transactional(rollbackFor = Exception.class)
    public PayOrder createOrReuse(PaymentContext ctx) {
        List<PayOrder> reusable = payOrderMapper.selectList(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getBizType, ctx.getBizType())
                .eq(PayOrder::getBizId, ctx.getBizId())
                .eq(PayOrder::getChannelCode, ctx.getChannelCode())
                .in(PayOrder::getStatus,
                        PayOrderState.CREATED.code(),
                        PayOrderState.PAYING.code())
                .orderByDesc(PayOrder::getCreateTime));

        String currCode = ctx.getCurrCode() == null ? "CNY" : ctx.getCurrCode();
        for (PayOrder existing : reusable) {
            // 金额变了（后台改价、用户改了优惠）就不能复用旧支付单——复用会让用户按旧价付款。
            // 币种也要一致：渠道出示币种变了时旧单同样不能复用，否则表单与回调验签会各拿一个币种。
            boolean sameAmount = existing.getAmount() != null && existing.getAmount().compareTo(ctx.getAmount()) == 0;
            if (sameAmount && CurrencyCodes.same(currCode, existing.getCurrCode())) {
                log.info("复用未终态支付单 payNo={} bizType={} bizId={} channel={}",
                        existing.getPayNo(), ctx.getBizType(), ctx.getBizId(), ctx.getChannelCode());
                // 前端这次给的回跳地址可能和上次不同（换了入口页），以最新一次为准
                JSONObject attach = buildAttach(ctx);
                if (attach != null && !attach.equals(existing.getAttach())) {
                    // 只写 attach 一列：不拿读快照整行写回（状态、退款额度列另有 NEVER 保护，这里连机会都不给）
                    PayOrder patch = new PayOrder();
                    patch.setId(existing.getId());
                    patch.setAttach(attach);
                    payOrderMapper.updateById(patch);
                    existing.setAttach(attach);
                }
                return existing;
            }
            log.info("支付单 payNo={} 金额或币种口径已变（{} {} → {} {}），不复用，另建新单",
                    existing.getPayNo(), existing.getAmount(), existing.getCurrCode(), ctx.getAmount(), currCode);
        }

        PayOrder payOrder = new PayOrder();
        payOrder.setPayNo(payNoGenerator.next());
        payOrder.setOrderNo(ctx.getOrderNo());
        payOrder.setBizType(ctx.getBizType());
        payOrder.setBizId(ctx.getBizId());
        payOrder.setChannelCode(ctx.getChannelCode());
        payOrder.setCurrCode(currCode);
        payOrder.setTenantId(ctx.getTenantId());
        payOrder.setAmount(ctx.getAmount());
        payOrder.setAmountMinor(ctx.getAmount().movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact());
        payOrder.setCurrency(CurrencyCodes.alpha(currCode));
        PayOrderStateSync.initCreated(payOrder);
        payOrder.setClientIp(ctx.getClientIp());
        payOrder.setAttach(buildAttach(ctx));
        payOrderMapper.insert(payOrder);
        log.info("新建支付单 payNo={} bizType={} bizId={} channel={} amount={} {}",
                payOrder.getPayNo(), ctx.getBizType(), ctx.getBizId(),
                ctx.getChannelCode(), payOrder.getAmount(), payOrder.getCurrency());
        return payOrder;
    }

    public PayOrder getByPayNo(String payNo) {
        return payOrderMapper.selectByPayNo(payNo);
    }

    /** 支付单附加信息里存前端回跳地址的键。托管收银台类渠道付完要把顾客带回这个页面。 */
    public static final String ATTACH_RETURN_URL = "returnUrl";

    /** 支付单附加信息里存前端「放弃支付」回跳地址的键。 */
    public static final String ATTACH_QUIT_URL = "quitUrl";

    /** 支付单附加信息里存顾客选定的具体支付方式的键（托管收银台类渠道用于区分不同子支付方式）。 */
    public static final String ATTACH_PAY_METHOD = "payMethod";

    /**
     * 把前端给的回跳地址与选定支付方式落到支付单上。
     *
     * <p>托管收银台类渠道的表单是在中转页按 payNo <b>事后</b>现算的，
     * 那时已经没有发起支付时的上下文；不落库就只能写死一个路径——
     * 2026-09-11 联调实测写死的 {@code /pay/result} 在 H5 里根本不存在，顾客付完款回到一张白页。
     * 同理，顾客选的具体支付方式也要落库，中转页才能据此预选、跳过方式列表页。
     */
    static JSONObject buildAttach(PaymentContext ctx) {
        String payMethod = ctx.getExtra() == null ? null
                : java.util.Objects.toString(ctx.getExtra().get(PaymentContext.EXTRA_PAY_METHOD), null);
        if (StrUtil.isBlank(ctx.getReturnUrl()) && StrUtil.isBlank(ctx.getQuitUrl())
                && StrUtil.isBlank(payMethod)) {
            return null;
        }
        JSONObject attach = new JSONObject();
        if (StrUtil.isNotBlank(ctx.getReturnUrl())) {
            attach.set(ATTACH_RETURN_URL, ctx.getReturnUrl());
        }
        if (StrUtil.isNotBlank(ctx.getQuitUrl())) {
            attach.set(ATTACH_QUIT_URL, ctx.getQuitUrl());
        }
        if (StrUtil.isNotBlank(payMethod)) {
            attach.set(ATTACH_PAY_METHOD, payMethod);
        }
        return attach;
    }

    /**
     * 按业务订单号 + 渠道找最近一张支付单。
     *
     * <p>取消订单前的网关复核要用：那条链路手里只有订单，没有支付单号。
     * 取最近一张是因为同一订单换过渠道时会有多张支付单，
     * 而复核关心的是「当前这个渠道上有没有付过」。
     *
     * <p><b>只取商品订单的支付单（biz_type = ORDER）</b>：同一个单号不能误取到别的业务类型的支付单。
     */
    public PayOrder findLatestByOrderNoAndChannel(String orderNo, String channelCode) {
        if (orderNo == null || orderNo.isBlank()) {
            return null;
        }
        List<PayOrder> found = payOrderMapper.selectList(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getOrderNo, orderNo)
                .eq(PayOrder::getChannelCode, channelCode)
                .eq(PayOrder::getBizType, PayBizTypeEnum.ORDER.getValue())
                .orderByDesc(PayOrder::getCreateTime)
                .last("limit 1"));
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 一张订单的全部支付单（biz_type = ORDER），按订单 id（biz_id）取。
     * 订单号会被后台改价重签，所以不按 order_no。取消前的渠道复核逐张查单用。
     */
    public List<PayOrder> listOrderPayOrders(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return List.of();
        }
        List<PayOrder> found = payOrderMapper.selectList(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getBizType, PayBizTypeEnum.ORDER.getValue())
                .eq(PayOrder::getBizId, orderId));
        return found == null ? List.of() : found;
    }

    /** P2 渠道下单成功 → PAYING。NOOP（已是 PAYING，复用单）也算成功。 */
    public boolean markPaying(String payNo) {
        Transition<PayOrderState> t = machine().fire(payNo, PayOrderEvent.CHANNEL_ORDERED,
                PayOrderTransitionContext.builder().operatorType(Operator.USER).reason("渠道下单成功").build());
        return !t.isRejected();
    }

    /** 确认收款的判定结果：本次用了哪个事件（P4 / P7 / P9）与状态机的结论。 */
    public record PaidOutcome(PayOrderEvent event, Transition<PayOrderState> transition) {
    }

    /**
     * 确认收款（第⑩步）：按支付单当前状态与「同业务单是否已有别的已付支付单」选 P4 / P7 / P9。
     *
     * <p>调用方必须<b>先在锁外完成渠道回查</b>，本方法内不做任何对外 HTTP；且应持有业务单的锁
     * （订单：{@code lock:order:{bizId}}），让「同业务单无其他 PAID」这条判定不被并发的另一张支付单穿透（末段）。
     */
    public PaidOutcome confirmPaid(PayOrder snapshot, String channelTxnId, LocalDateTime payTime, String callbackLogId) {
        boolean otherPaid = otherPaidExists(snapshot);
        PayOrderState now = PayOrderState.of(snapshot.getStatus());
        PayOrderEvent event = switch (now) {
            case CREATED, PAYING -> otherPaid ? PayOrderEvent.DUPLICATE_PAY_CONFIRMED : PayOrderEvent.PAY_CONFIRMED;
            case CANCELLED -> otherPaid ? PayOrderEvent.DUPLICATE_PAY_CONFIRMED : PayOrderEvent.LATE_PAY_CONFIRMED;
            case FAILED -> PayOrderEvent.LATE_PAY_CONFIRMED;
            default -> PayOrderEvent.PAY_CONFIRMED; // 已付类：状态机按流水号判 NOOP / DUP_CHARGE
        };
        Transition<PayOrderState> t = machine().fire(snapshot.getPayNo(), event,
                PayOrderTransitionContext.builder()
                        .operatorType(Operator.CHANNEL)
                        .channelTxnId(channelTxnId)
                        .payTime(payTime)
                        .otherPaidExists(otherPaid)
                        .callbackLogId(callbackLogId)
                        .reason("渠道确认收款")
                        .build());
        return new PaidOutcome(event, t);
    }

    /** 同业务单是否已有另一张收到钱的支付单（PAID）。 */
    boolean otherPaidExists(PayOrder p) {
        return payOrderMapper.selectCount(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getBizType, p.getBizType())
                .eq(PayOrder::getBizId, p.getBizId())
                .ne(PayOrder::getPayNo, p.getPayNo())
                .eq(PayOrder::getStatus, PayOrderState.PAID.code())) > 0;
    }

    /** P5 渠道明确失败（UNKNOWN / 处理中不要调）。 */
    public boolean markFailed(String payNo, String failReason) {
        return machine().fire(payNo, PayOrderEvent.CHANNEL_FAILED,
                PayOrderTransitionContext.builder().operatorType(Operator.CHANNEL).failReason(failReason).build()).isOk();
    }

    /**
     * P6：订单关闭时本地关掉它的未付支付单（CREATED / PAYING）。渠道关单能力未接入前一律 channel_closed=false，
     * 迟到到账走 P7 + 差错池兜底。
     *
     * @return 本次关掉的张数
     */
    public int closeOpenPayOrders(String orderId, String reason) {
        List<PayOrder> open = payOrderMapper.selectList(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getBizType, PayBizTypeEnum.ORDER.getValue())
                .eq(PayOrder::getBizId, orderId)
                .in(PayOrder::getStatus, PayOrderState.CREATED.code(), PayOrderState.PAYING.code()));
        int closed = 0;
        for (PayOrder p : open) {
            Transition<PayOrderState> t = machine().fire(p.getPayNo(), PayOrderEvent.ORDER_CLOSED,
                    PayOrderTransitionContext.builder().operatorType(Operator.SYSTEM).channelClosed(false)
                            .reason(reason).build());
            if (t.isOk()) {
                closed++;
                if (channelCloser != null) {
                    channelCloser.closeAfterCommit(p.getChannelCode(), p.getPayNo());
                }
            }
            else if (t.isRejected()) {
                log.warn("订单 {} 关闭时支付单 {} 未能本地关单：{}", orderId, p.getPayNo(), t.reason());
            }
        }
        return closed;
    }

    static long toMinor(BigDecimal yuan) {
        return yuan.setScale(2, java.math.RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    private PayOrderStateMachine machine() {
        if (stateMachine == null) {
            throw new IllegalStateException("未装配支付单状态机");
        }
        return stateMachine;
    }
}
