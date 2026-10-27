/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.lock.annotation.DistributedLock;
import com.zhiflow.common.lock.enums.LockTimeoutStrategy;
import com.zhiflow.common.portal.util.ApiUtil;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import com.zhiflow.common.security.internal.InternalCallContext;
import com.zhiflow.mall.api.event.OrderCancelledEvent;
import com.zhiflow.mall.api.event.OrderCreatedEvent;
import com.zhiflow.mall.api.event.OrderPaidEvent;
import com.zhiflow.mall.api.mapper.GoodsSkuMapper;
import com.zhiflow.mall.api.mapper.GoodsSkuSpecValueMapper;
import com.zhiflow.mall.api.mapper.OrderInfoMapper;
import com.zhiflow.mall.api.mapper.OrderItemMapper;
import com.zhiflow.mall.api.mapper.OrderRecordMapper;
import com.zhiflow.mall.api.payment.PayOrderService;
import com.zhiflow.mall.api.payment.PaymentChannelRegistry;
import com.zhiflow.mall.api.payment.dto.PaymentQueryResult;
import com.zhiflow.mall.api.service.GoodsSpuService;
import com.zhiflow.mall.api.service.OrderInfoService;
import com.zhiflow.mall.api.service.OrderItemService;
import com.zhiflow.mall.api.service.ShopInfoService;
import com.zhiflow.mall.api.service.ShoppingCartService;
import com.zhiflow.mall.api.service.UserAddressService;
import com.zhiflow.mall.api.service.UserInfoService;
import com.zhiflow.mall.api.trade.OrderStockDeduction;
import com.zhiflow.mall.api.trade.PaymentFactRecorder;
import com.zhiflow.mall.api.trade.StockHoldService;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.dto.PaymentCheckResult;
import com.zhiflow.mall.common.dto.PlaceOrderDTO;
import com.zhiflow.mall.common.dto.PlaceOrderSkuDTO;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSkuSpecValue;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderItem;
import com.zhiflow.mall.common.entity.OrderRecord;
import com.zhiflow.mall.common.entity.PayOrder;
import com.zhiflow.mall.common.entity.ShopInfo;
import com.zhiflow.mall.common.entity.ShoppingCart;
import com.zhiflow.mall.common.entity.UserAddress;
import com.zhiflow.mall.common.entity.UserInfo;
import com.zhiflow.mall.common.enums.OptSourceEnum;
import com.zhiflow.mall.common.enums.OrderInfoEnum;
import com.zhiflow.mall.common.enums.OrderRecordTypeEnum;
import com.zhiflow.mall.common.observability.BizMetrics;
import com.zhiflow.mall.common.orderno.OrderNoGenerator;
import com.zhiflow.mall.common.trade.item.ItemStatus;
import com.zhiflow.mall.common.trade.payexception.PayExceptionRecorder;
import com.zhiflow.mall.common.trade.statemachine.Operator;
import com.zhiflow.mall.common.trade.statemachine.Transition;
import com.zhiflow.mall.common.trade.statemachine.order.OrderEvent;
import com.zhiflow.mall.common.trade.statemachine.order.OrderPatch;
import com.zhiflow.mall.common.trade.statemachine.order.OrderState;
import com.zhiflow.mall.common.trade.statemachine.order.OrderStateMachine;
import com.zhiflow.mall.common.trade.statemachine.order.OrderStateSync;
import com.zhiflow.mall.common.trade.statemachine.order.OrderTransitionContext;
import com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderEvent;
import com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderState;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商城订单（单店铺、快递配送、无营销）。
 *
 * <p>订单状态只经 {@link OrderStateMachine} 写入：下单 → 待付款，付款成功（O4）→ 待发货，
 * 发货（后台）→ 待收货，确认收货（O12）→ 已完成，取消（O5）→ 已关闭。
 * 取消、支付入账、余额无关的写操作都持同一把订单锁 {@code order:{id}}，互斥执行。
 *
 * @date 2019-09-10 15:21:22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderInfoServiceImpl extends ServiceImpl<OrderInfoMapper, OrderInfo> implements OrderInfoService {

    /**
     * 自引用，用于在内部方法间通过代理调用，确保 @DistributedLock / @Transactional 生效。
     * 使用 @Lazy 打破构造期循环依赖。
     */
    @Autowired
    @Lazy
    private OrderInfoService self;

    private final OrderRecordMapper orderRecordMapper;
    private final OrderNoGenerator orderNoGenerator;
    private final GoodsSkuMapper goodsSkuMapper;
    /** 库存预占（下单写 HOLD、付款 CONFIRMED、取消只回补真的放出的行）。测试里可为空 → 逐项回补。 */
    private final StockHoldService stockHoldService;
    /** 扣库存的等锁上限与加锁顺序。测试里可为空 → 按关处理，见 {@link #stockDeduction()}。 */
    private final OrderStockDeduction orderStockDeduction;
    private final GoodsSpuService goodsSpuService;
    private final OrderItemService orderItemService;
    private final GoodsSkuSpecValueMapper goodsSkuSpecValueMapper;
    private final ShoppingCartService shoppingCartService;
    private final UserAddressService userAddressService;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserInfoService userInfoService;
    private final ShopInfoService shopInfoService;
    private final OrderItemMapper orderItemMapper;
    private final OrderValidatorService orderValidatorService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TransactionTemplate transactionTemplate;
    /**
     * 取消订单前的渠道复核。
     *
     * <p>⚠️ 只能注入注册表与支付单服务，<b>不要注入 {@code PaymentService}</b>——
     * 那会形成 {@code PaymentService → 入账处理器 → 本类 → PaymentService} 的循环引用。
     */
    private final PaymentChannelRegistry paymentChannelRegistry;
    private final PayOrderService payOrderService;
    /** 迟到到账 / 重复到账的差错池落点。 */
    private final PaymentFactRecorder paymentFactRecorder;
    /** 订单状态的唯一写入口。 */
    private final OrderStateMachine orderStateMachine;

    /**
     * 「支付状态无法判定」的逃生门时限。超过这个订单年龄，unknown 降级为放行取消，
     * 否则查不通的订单会永久取消不掉、库存被锁死。取消后才到的钱入差错池 LATE_PAID 处置。
     */
    private static final long UNKNOWN_ESCAPE_HATCH_HOURS = 2;

    /** 同一张单从首次查不通起持续满这么久才照常取消。按时间而不是按调用次数：取消入口很多，按次数几秒就会凑满。 */
    static final Duration UNKNOWN_GRACE = Duration.ofMinutes(15);

    /** 首次查不通的时间（应用时钟 epoch 毫秒），{@code SET NX}，1 天过期且不续期。 */
    static final String UNKNOWN_SINCE_KEY = "order:cancel-unknown-since:";

    static final Duration UNKNOWN_SINCE_TTL = Duration.ofDays(1);

    /** 下单事务超时（秒）：卡在热点行锁 / 慢 SQL 的事务不能一直攥着连接。 */
    static final int ORDER_TX_TIMEOUT_SECONDS = 5;

    @Override
    public IPage<OrderInfo> page2(IPage<OrderInfo> page, OrderInfo orderInfo) {
        // 「全部」tab 的排序由 selectPage2 的 ORDER BY 负责（在途优先分桶 + 时间倒序）。
        // 分页插件会用 page.orders() 覆盖掉 SQL 里的 ORDER BY，这里先清掉调用方带来的排序。
        if (orderInfo.getStatus() == null && page.orders() != null && !page.orders().isEmpty()) {
            page.orders().clear();
        }
        IPage<OrderInfo> result = baseMapper.selectPage2(page, orderInfo);
        if (result == null || result.getRecords() == null || result.getRecords().isEmpty()) {
            return result;
        }
        List<OrderInfo> orders = result.getRecords();
        // 两段式批量装配 listOrderItem / shopInfo（每页固定 2 条 SQL）
        List<String> orderIds = orders.stream().map(OrderInfo::getId).collect(Collectors.toList());
        Map<String, List<OrderItem>> itemsByOrderId = new HashMap<>();
        for (OrderItem item : orderItemMapper.selectListByOrderIds(orderIds)) {
            itemsByOrderId.computeIfAbsent(item.getOrderId(), k -> new ArrayList<>()).add(item);
        }
        // 店铺按去重后的 id 逐个走 mall:shop_info 缓存（单店铺部署通常只有 1 个，命中时 0 条 SQL）
        Map<String, ShopInfo> shopById = new HashMap<>();
        orders.stream().map(OrderInfo::getShopId).filter(Objects::nonNull).distinct().forEach(shopId -> {
            ShopInfo shop = shopInfoService.getById(shopId);
            if (shop != null) {
                shopById.put(shopId, shop);
            }
        });

        List<OrderInfo> validOrders = new ArrayList<>();
        for (OrderInfo order : orders) {
            order.setListOrderItem(itemsByOrderId.getOrDefault(order.getId(), new ArrayList<>()));
            order.setShopInfo(order.getShopId() == null ? null : shopById.get(order.getShopId()));
            if (isPendingPay(order)) {
                long minutesPassed = Duration.between(order.getCreateTime(), LocalDateTime.now()).toMinutes();
                if (minutesPassed > MallConstants.ORDER_TIME_OUT_0) {
                    // 超时未取消的订单（自动取消任务还没轮到）：就地补偿一次，并从列表剔除
                    lazyCancel(order);
                    continue;
                }
                order.setOutTime(Math.max(0, (MallConstants.ORDER_TIME_OUT_0 - minutesPassed) * 60));
            }
            validOrders.add(order);
        }
        // 不更新 total：分页总数保持原查询结果
        result.setRecords(validOrders);
        return result;
    }

    @Override
    public OrderInfo getById2(Serializable id) {
        OrderInfo orderInfo = baseMapper.selectById2(id);
        if (orderInfo == null) {
            return null;
        }
        fillShopInfo(orderInfo);
        List<OrderRecord> orderRecordList = orderRecordMapper.selectList(Wrappers.<OrderRecord>lambdaQuery()
                .eq(OrderRecord::getOrderId, orderInfo.getId()).orderByAsc(OrderRecord::getCreateTime));
        orderRecordList.forEach(t -> t.setRecordSource(OptSourceEnum.getByName(t.getRecordSource()).getValue()));
        orderInfo.setListOrderRecord(orderRecordList);
        if (isPendingPay(orderInfo)) {
            long minutesPassed = Duration.between(orderInfo.getCreateTime(), LocalDateTime.now()).toMinutes();
            if (minutesPassed > MallConstants.ORDER_TIME_OUT_0) {
                lazyCancel(orderInfo);
                OrderInfo reloaded = baseMapper.selectById2(id);
                if (reloaded != null) {
                    fillShopInfo(reloaded);
                    reloaded.setListOrderRecord(orderInfo.getListOrderRecord());
                    orderInfo = reloaded;
                }
            }
            else {
                // 自动取消倒计时（秒）
                orderInfo.setOutTime(Math.max(0, (MallConstants.ORDER_TIME_OUT_0 - minutesPassed) * 60));
            }
        }
        return orderInfo;
    }

    /** 订单里的店铺走 mall:shop_info 缓存（原来是 resultMap 里嵌套查一次 shop_info）。 */
    private void fillShopInfo(OrderInfo order) {
        order.setShopInfo(order.getShopId() == null ? null : shopInfoService.getById(order.getShopId()));
    }

    private static boolean isPendingPay(OrderInfo order) {
        return CommonConstants.NO.equals(order.getIsPay()) && order.getStatus() == null;
    }

    /** 查询时遇到超时未取消的订单就地补偿取消一次；失败只记日志（自动取消任务会再来）。 */
    private void lazyCancel(OrderInfo order) {
        log.warn("查询时发现超时未自动取消的订单: {}, 创建时间: {}", order.getOrderNo(), order.getCreateTime());
        try {
            // 必须通过 self 走代理调用，否则 @DistributedLock 不生效
            self.orderCancelV2(order.getId(), OptSourceEnum.SYSTEM);
        }
        catch (Exception e) {
            log.error("懒补偿：取消超时订单失败 {}", order.getOrderNo(), e);
        }
    }

    // ────────────────────────────── 取消前的渠道复核 ──────────────────────────────

    /**
     * 取消前问一遍渠道：本单的每张未结的支付单都查一次。
     *
     * <p>用户可能先选微信没付、又换支付宝付了，所以不能只查最后一次选的渠道。
     * 查不通一律返回 {@code unknown()} 而<b>不是</b> {@code notPaid()}：后者会让取消流程把一笔其实已付的订单取消掉并回滚库存。
     */
    PaymentCheckResult checkPaymentStatus(OrderInfo orderInfo) {
        List<PayOrder> payOrders = payOrderService.listOrderPayOrders(orderInfo.getId()).stream()
                .filter(p -> PayOrderState.CREATED.code().equals(p.getStatus())
                        || PayOrderState.PAYING.code().equals(p.getStatus())
                        || PayOrderState.PAID.code().equals(p.getStatus()))
                .toList();
        boolean unknown = false;
        for (PayOrder payOrder : payOrders) {
            if (!paymentChannelRegistry.supports(payOrder.getChannelCode())) {
                log.warn("订单 {} 的支付单 {} 渠道 {} 未注册，按无法判定处理", orderInfo.getOrderNo(), payOrder.getPayNo(),
                        payOrder.getChannelCode());
                unknown = true;
                continue;
            }
            try {
                PaymentQueryResult result = paymentChannelRegistry.require(payOrder.getChannelCode()).query(payOrder);
                if (result.isPaid()) {
                    return PaymentCheckResult.paidOnPayOrder(payOrder.getChannelCode(), payOrder.getPayNo(),
                            result.getChannelTxnId(), result.getPayTime(), null);
                }
                unknown |= result.isUnknown();
            }
            catch (Exception e) {
                // 查单异常绝不能冒到取消流程外面去打断它，也绝不能被读成「未支付」
                log.error("渠道 {} 查单异常 orderNo={} payNo={}", payOrder.getChannelCode(), orderInfo.getOrderNo(),
                        payOrder.getPayNo(), e);
                unknown = true;
            }
        }
        return unknown ? PaymentCheckResult.unknown() : PaymentCheckResult.notPaid();
    }

    /**
     * unknown 状态下是否放行取消：同一张单从首次「查不通」起持续满 {@link #UNKNOWN_GRACE} 就照常取消，
     * 取消之后才到的钱走差错池 LATE_PAID。首次时间记在 Redis；Redis 不可用时只按订单年龄
     * {@link #UNKNOWN_ESCAPE_HATCH_HOURS} 小时兜底，不会永久锁库存。
     */
    boolean unknownEscapeHatchOpen(OrderInfo orderInfo) {
        Long elapsedMs = null;
        try {
            String key = UNKNOWN_SINCE_KEY + orderInfo.getId();
            long now = System.currentTimeMillis();
            Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(now), UNKNOWN_SINCE_TTL);
            if (Boolean.TRUE.equals(first)) {
                elapsedMs = 0L;
            }
            else if (Boolean.FALSE.equals(first)) {
                String since = stringRedisTemplate.opsForValue().get(key);
                elapsedMs = since == null ? 0L : Math.max(0L, now - Long.parseLong(since));
            }
        }
        catch (Exception e) {
            log.warn("订单 {} 读写「首次查不通」时间失败，按订单年龄兜底", orderInfo.getOrderNo(), e);
        }
        LocalDateTime createTime = orderInfo.getCreateTime();
        boolean byAge = createTime == null
                || createTime.isBefore(LocalDateTime.now().minusHours(UNKNOWN_ESCAPE_HATCH_HOURS));
        if ((elapsedMs != null && elapsedMs >= UNKNOWN_GRACE.toMillis()) || byAge) {
            log.error("订单 {} 支付状态持续无法判定（下单于 {}），照常取消；取消后才到的钱入差错池 LATE_PAID 处置",
                    orderInfo.getOrderNo(), createTime);
            return true;
        }
        log.warn("订单 {} 支付状态无法判定，本次不取消", orderInfo.getOrderNo());
        return false;
    }

    /**
     * 取消前查单发现支付单已付：先把支付单确认为已付（支付单状态机在订单锁内判定 P4 / P7 / P9），再推进订单。
     *
     * @return true = 继续推进订单；false = 这是同一订单的重复付款（P9），订单不动
     */
    private boolean confirmPayOrderBeforeSettle(PaymentCheckResult paid) {
        if (StrUtil.isBlank(paid.getPayNo())) {
            return true;
        }
        PayOrder po = payOrderService.getByPayNo(paid.getPayNo());
        if (po == null) {
            return true;
        }
        PayOrderService.PaidOutcome out = payOrderService.confirmPaid(po, paid.getTransactionId(),
                paid.getPaymentTime(), "CANCEL_CHECK");
        if (out.transition().isRejected()) {
            log.error("取消前查单发现支付单 {} 已付，但支付单状态机拒绝确认：{}——本轮不取消、不入账",
                    paid.getPayNo(), out.transition().reason());
            throw new BizException("支付状态确认中，请稍后重试");
        }
        return out.event() != PayOrderEvent.DUPLICATE_PAY_CONFIRMED;
    }

    // ────────────────────────────── 取消 ──────────────────────────────

    /**
     * 后台取消（经 {@code /orderinfo/inside/cancel}）。与 {@link #orderCancelV2} 同一套判定与回补。
     */
    @DistributedLock(name = "order", keys = {"#orderInfo.getId()"}, lockTimeoutStrategy = LockTimeoutStrategy.FAIL_FAST)
    @Override
    public void orderCancel(OrderInfo orderInfo, OptSourceEnum optSource) {
        cancelLocked(orderInfo.getId(), optSource);
    }

    /**
     * 买家取消 / 超时自动取消 / 查询懒补偿。持订单锁，与支付入账互斥。
     */
    @DistributedLock(name = "order", keys = {"#orderId"}, lockTimeoutStrategy = LockTimeoutStrategy.FAIL_FAST)
    @Override
    public void orderCancelV2(String orderId, OptSourceEnum optSource) {
        cancelLocked(orderId, optSource);
    }

    /** 调用方已持订单锁。 */
    private void cancelLocked(String orderId, OptSourceEnum optSource) {
        // 锁内重读最新状态
        OrderInfo orderInfo = baseMapper.selectById(orderId);
        if (orderInfo == null) {
            log.warn("Order not found: {}", orderId);
            return;
        }
        if (!CommonConstants.NO.equals(orderInfo.getIsPay())) {
            log.info("Order {} is already paid (isPay={}), cannot cancel", orderInfo.getOrderNo(), orderInfo.getIsPay());
            return;
        }
        if (OrderInfoEnum.STATUS_5.getValue().equals(orderInfo.getStatus())) {
            log.info("Order {} is already cancelled", orderInfo.getOrderNo());
            return;
        }

        // 渠道复核：用户可能已经付了、只是回调还没到
        PaymentCheckResult paymentResult = checkPaymentStatus(orderInfo);
        if (paymentResult.isUnknown() && !unknownEscapeHatchOpen(orderInfo)) {
            log.error("订单 {} 支付状态无法判定（渠道不可达或响应异常），本轮不取消，等待重试", orderInfo.getOrderNo());
            throw new BizException("支付状态确认中，请稍后重试");
        }
        if (paymentResult.isPaid()) {
            if (!confirmPayOrderBeforeSettle(paymentResult)) {
                return;
            }
            log.warn("Order {} was paid during cancellation, syncing payment status", orderInfo.getOrderNo());
            orderInfo.setPaymentType(paymentResult.getPaymentType());
            orderInfo.setTransactionId(paymentResult.getTransactionId());
            orderInfo.setPaymentTime(paymentResult.getPaymentTime());
            orderInfo.setTradeType(paymentResult.getTradeType());
            orderInfo.setChannelPayNo(paymentResult.getPayNo());
            // 必须走 self（Spring 代理）：this 自调用会绕过 @Transactional，OrderPaidEvent 的 AFTER_COMMIT 监听器收不到
            self.notifyOrder(orderInfo);
            return;
        }

        List<OrderItem> listOrderItem = orderItemService.list(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderId, orderInfo.getId()));
        Boolean cancelled = transactionTemplate.execute(status -> {
            // 状态与 cancel_time 由状态机在锁内条件更新写入（O5），成功才回补库存
            if (!fireCancel(orderInfo, optSource)) {
                return false;
            }
            // 有库存预占的订单只回补「这次真的从 HOLD 放出来的」行（与兜底任务并发也只回补一次）
            boolean stockViaHold = stockHoldService != null
                    && stockHoldService.restoreOnCancel(orderInfo.getId(), goodsSkuMapper::increaseStock);
            if (!stockViaHold) {
                restoreStockInLockOrder(listOrderItem);
            }
            recordCancelInTx(orderInfo, optSource);
            return true;
        });
        if (Boolean.TRUE.equals(cancelled)) {
            log.info("Order {} cancelled", orderInfo.getOrderNo());
        }
    }

    /**
     * 取消回补库存（无库存预占行的订单）：按 skuId 升序逐项回补，与下单扣减 {@link OrderStockDeduction#LOCK_ORDER} 同一加锁顺序，
     * 多 SKU 订单的取消与另一张反序明细的下单 / 取消并发时不会互等成死锁。
     * skuId 为空的项跳过。在调用方的取消事务里执行。
     */
    private void restoreStockInLockOrder(List<OrderItem> items) {
        items.stream()
                .filter(i -> StrUtil.isNotBlank(i.getSkuId()))
                .sorted(OrderStockDeduction.LOCK_ORDER)
                .forEach(i -> goodsSkuMapper.increaseStock(i.getSkuId(), i.getQuantity()));
    }

    /**
     * 取消成功（状态机已跳到已取消、回补已做）后、仍在<b>同一个取消事务</b>里：写 CANCEL 操作记录（同类型去重），
     * 并发布 {@link OrderCancelledEvent}。事件约定是「提交后处理」：监听方用
     * {@code @TransactionalEventListener(AFTER_COMMIT)}，必须在事务里发布才会在提交后执行、回滚则不执行。
     * 原来在 {@code transactionTemplate.execute} 返回之后才发布（无事务 ⇒ 这类监听器静默跳过；开源版目前没有监听方，
     * 所以没有可见后果），2026-10-26 一并改正。
     */
    private void recordCancelInTx(OrderInfo orderInfo, OptSourceEnum optSource) {
        saveOrderRecord(orderInfo.getId(), OrderRecordTypeEnum.CANCEL, optSource);
        applicationEventPublisher.publishEvent(new OrderCancelledEvent(this, orderInfo, optSource));
    }

    static Operator operatorOf(OptSourceEnum optSource) {
        if (optSource == null) {
            return Operator.SYSTEM;
        }
        return switch (optSource) {
            case USER -> Operator.USER;
            case SELLER -> Operator.ADMIN;
            case SYSTEM -> Operator.SYSTEM;
        };
    }

    /**
     * 取消（O5）走状态机：成功才做回补；已取消 = 幂等无操作；被拒（已付、状态不对）抛业务异常。
     *
     * @return true = 本次真的取消了
     */
    boolean fireCancel(OrderInfo orderInfo, OptSourceEnum optSource) {
        LocalDateTime now = LocalDateTime.now();
        Transition<OrderState> t = orderStateMachine.fire(orderInfo.getId(), OrderEvent.CANCEL,
                OrderTransitionContext.builder()
                        .operatorType(operatorOf(optSource))
                        .requestId(InternalCallContext.currentOperationId())
                        .reason("取消订单（" + (optSource == null ? "SYSTEM" : optSource.name()) + "）")
                        .patch(OrderPatch.builder().cancelTime(now).build())
                        .build());
        if (t.isRejected()) {
            throw new BizException("订单当前状态不能取消：" + t.reason());
        }
        if (t.isNoop()) {
            log.info("订单 {} 已是取消状态，幂等跳过回补", orderInfo.getOrderNo());
            return false;
        }
        OrderStateSync.apply(orderInfo, t.to());
        orderInfo.setCancelTime(now);
        return true;
    }

    // ────────────────────────────── 收货 ──────────────────────────────

    @Override
    @Transactional(timeout = 5, rollbackFor = Exception.class)
    public void orderReceive(OrderInfo orderInfo, OptSourceEnum optSource) {
        // 确认收货（O12）走状态机：锁内重读 + 原状态条件更新 + 流水。双击 / 自动收货并发时只有一个能赢
        LocalDateTime receivedAt = LocalDateTime.now();
        Transition<OrderState> received = orderStateMachine.fire(orderInfo.getId(), OrderEvent.RECEIVE,
                OrderTransitionContext.builder()
                        .operatorType(operatorOf(optSource))
                        .requestId(InternalCallContext.currentOperationId())
                        .reason("确认收货")
                        .patch(OrderPatch.builder()
                                .receiverTime(receivedAt)
                                .build())
                        .build());
        if (!received.isOk()) {
            log.warn("订单 {} 不在可确认收货的状态（{}：{}），跳过", orderInfo.getOrderNo(), received.outcome(),
                    received.reason());
            return;
        }
        OrderStateSync.apply(orderInfo, received.to());
        orderInfo.setReceiverTime(receivedAt);
        saveOrderRecord(orderInfo.getId(), OrderRecordTypeEnum.RECEIVE, optSource);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        orderItemService.remove(Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderId, id));//删除订单详情
        return super.removeById(id);
    }

    // ────────────────────────────── 下单 ──────────────────────────────

    @Override
    public UserAddress requireOwnShippingAddress(PlaceOrderDTO placeOrderDTO) {
        String addressId = placeOrderDTO.getUserAddressId();
        UserAddress address = StringUtils.isBlank(addressId) ? null : userAddressService.getById(addressId);
        if (address == null) {
            throw new BizException("请选择收货地址");
        }
        if (StringUtils.isBlank(placeOrderDTO.getUserId()) || !placeOrderDTO.getUserId().equals(address.getUserId())) {
            log.warn("下单收货地址不属于下单人，拒单 userId={} userAddressId={}", placeOrderDTO.getUserId(), addressId);
            throw new BizException("无权操作");
        }
        return address;
    }

    @Override
    @Transactional(timeout = ORDER_TX_TIMEOUT_SECONDS, rollbackFor = Exception.class)
    public OrderInfo orderAddOne(HttpServletRequest request, PlaceOrderDTO placeOrderDTO) throws Exception {
        if (StringUtils.isBlank(placeOrderDTO.getUserId())) {
            placeOrderDTO.setUserId(ThirdSessionHolder.getUserId());
        }
        // 被拉黑（user_info.black_flag='1'）的客户禁止下单
        UserInfo buyer = userInfoService.getById(placeOrderDTO.getUserId());
        if (buyer != null && "1".equals(buyer.getBlackFlag())) {
            throw new BizException("您的账号已被限制下单，请联系客服");
        }
        // 只有快递配送；单商品类型
        placeOrderDTO.setDeliveryWay(MallConstants.DELIVERY_WAY_1);
        placeOrderDTO.setOrderType(MallConstants.ORDER_TYPE_0);
        // 单店铺：前端不传店铺就落默认店铺
        if (StringUtils.isBlank(placeOrderDTO.getShopId())) {
            placeOrderDTO.setShopId(CommonConstants.DEFAULT_SHOP_ID);
        }
        // 收货地址归属：放在任何写库之前，拿别人的地址 id 下单会读到对方的姓名电话地址。
        // 10-22：控制器核过的那条地址随 DTO 带进来（resolvedShippingAddress，JsonIgnore，只在进程内传），归属与 id 再对一次
        // 就直接复用，省掉事务里的第二次查询；没带或对不上就照旧查库核验。
        UserAddress shippingAddress = resolvedShippingAddressOrLoad(placeOrderDTO);
        if (StringUtils.isBlank(placeOrderDTO.getAppId()) && ThirdSessionHolder.getThirdSession() != null) {
            placeOrderDTO.setAppId(ThirdSessionHolder.getThirdSession().getAppId());
        }
        if (StringUtils.isBlank(placeOrderDTO.getAppType()) && request != null) {
            placeOrderDTO.setAppType(ApiUtil.getClientType(request));
        }
        if (CollUtil.isEmpty(placeOrderDTO.getSkus())) {
            throw new BizException("请选择商品");
        }
        Map<String, PlaceOrderSkuDTO> mapSkuDTO = placeOrderDTO.getSkus().stream()
                .collect(Collectors.toMap(PlaceOrderSkuDTO::getSkuId, Function.identity(), (a, b) -> {
                    throw new BizException("同一规格请合并数量后提交");
                }));
        List<GoodsSku> listSku = goodsSkuMapper.listSkuInOrderSub(placeOrderDTO.getShopId(), placeOrderDTO.getSkus());
        if (CollUtil.isEmpty(listSku)) {
            throw new BizException("商品不存在或库存不足");
        }

        OrderInfo orderInfo = new OrderInfo();
        BeanUtil.copyProperties(placeOrderDTO, orderInfo);
        orderInfo.setPaymentWay(MallConstants.PAYMENT_WAY_2);
        orderInfo.setShopId(placeOrderDTO.getShopId());
        OrderStateSync.initPendingPay(orderInfo);
        orderInfo.setOrderNo(orderNoGenerator.next());
        orderInfo.setSalesPrice(BigDecimal.ZERO);
        orderInfo.setPaymentPrice(BigDecimal.ZERO);
        orderInfo.setFreightPrice(BigDecimal.ZERO);
        orderInfo.setPaymentPoints(0);
        orderInfo.setPaymentCouponPrice(BigDecimal.ZERO);
        orderInfo.setPaymentPointsPrice(BigDecimal.ZERO);
        orderInfo.setCreateTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));

        // 规格值批量预取：一次查回本单全部 SKU 的规格值（listSku 已判空，IN 列表不会为空）
        Map<String, List<GoodsSkuSpecValue>> mapSkuSpecValue = goodsSkuSpecValueMapper
                .listGoodsSkuSpecValueBySkuIds(listSku.stream().map(GoodsSku::getId).collect(Collectors.toList()))
                .stream()
                .collect(Collectors.groupingBy(GoodsSkuSpecValue::getSkuId));

        List<OrderItem> listOrderItem = new ArrayList<>();
        for (GoodsSku goodsSku : listSku) {
            PlaceOrderSkuDTO placeOrderSkuDTO = mapSkuDTO.get(goodsSku.getId());
            GoodsSpu goodsSpu = goodsSku.getGoodsSpu();
            if (placeOrderSkuDTO == null || goodsSpu == null) {
                throw new BizException("参数错误");
            }
            // 只有已上架的商品能下单
            if (!CommonConstants.YES.equals(goodsSpu.getShelf()) || !MallConstants.GOODS_STATUS_1.equals(goodsSpu.getVerifyStatus())) {
                throw new BizException("商品【" + goodsSpu.getName() + "】已下架");
            }
            OrderItem orderItem = new OrderItem();
            orderItem.setShopId(orderInfo.getShopId());
            orderItem.setItemStatus(ItemStatus.NORMAL.name());
            orderItem.setSpuId(goodsSpu.getId());
            orderItem.setSkuId(goodsSku.getId());
            orderItem.setSpuName(goodsSpu.getName());
            orderItem.setPicUrl(StrUtil.isNotBlank(goodsSku.getPicUrl()) ? goodsSku.getPicUrl() : goodsSpu.getPicUrls()[0]);
            orderItem.setQuantity(placeOrderSkuDTO.getQuantity());
            // 金额一律服务端按 SKU 售价算，不读客户端传值
            orderValidatorService.priceOrderItem(orderItem, goodsSku);
            orderItem.setSpecInfo(mapSkuSpecValue.getOrDefault(goodsSku.getId(), Collections.emptyList()).stream()
                    .map(GoodsSkuSpecValue::getSpecValueName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("，")));
            listOrderItem.add(orderItem);
            orderInfo.setSalesPrice(orderInfo.getSalesPrice().add(orderItem.getPaymentPrice()));
            orderInfo.setPaymentPrice(orderInfo.getPaymentPrice().add(orderItem.getPaymentPrice()));
        }
        if (listOrderItem.size() != mapSkuDTO.size()) {
            // 有 SKU 不属于本店铺、已删或库存为 0（listSkuInOrderSub 已过滤）
            throw new BizException("部分商品已失效，请刷新后重新提交");
        }

        orderInfo.setRecName(shippingAddress.getUserName());
        orderInfo.setRecTel(shippingAddress.getTelNum());
        orderInfo.setRecAddress(shippingAddress.getProvinceName() + shippingAddress.getCityName()
                + shippingAddress.getCountyName() + shippingAddress.getDetailInfo());
        orderInfo.setAreaId(shippingAddress.getAreaId());
        orderInfo.setName(listOrderItem.getFirst().getSpuName());
        super.save(orderInfo);
        listOrderItem.forEach(orderItem -> orderItem.setOrderId(orderInfo.getId()));
        orderItemService.saveBatch(listOrderItem);

        // 删除购物车里已下单的商品：一条 DELETE，保留 (spu_id, sku_id) 配对
        shoppingCartService.remove(Wrappers.<ShoppingCart>lambdaQuery()
                .eq(ShoppingCart::getUserId, placeOrderDTO.getUserId())
                .and(cartQuery -> listOrderItem.forEach(item -> cartQuery
                        .or(pair -> pair.eq(ShoppingCart::getSpuId, item.getSpuId())
                                .eq(ShoppingCart::getSkuId, item.getSkuId())))));

        // 订单操作记录「创建」。10-22：订单 id 是本事务刚分配的，不可能已有同类型记录，不再先 COUNT 再 INSERT
        //（saveOrderRecord 的去重只对取消 / 收货 / 付款等可能重入的类型有意义，那些调用方不动）；
        // 并挪到扣库存之前——它只写新行、不碰任何已有行，不影响加锁顺序。
        insertOrderRecord(orderInfo.getId(), OrderRecordTypeEnum.CREATE, OptSourceEnum.USER);

        // 扣库存：带 stock >= qty 守卫的单列原子 UPDATE（RETURNING stock，售罄 / 低库存提交后清详情库存缓存），
        // 库存不足影响 0 行即抛错整单回滚。
        // 10-22：操作记录挪到扣减之前，扣减拿到 goods_sku 行锁后事务里只剩库存预占（开关关着不写）与 COMMIT，
        // 热点行的持锁窗口缩到提交本身。任何一步失败整单照旧回滚。
        stockDeduction().decrease(goodsSkuMapper, listOrderItem);
        // 同一事务写库存预占（开关关着不写）
        if (stockHoldService != null) {
            stockHoldService.hold(orderInfo, listOrderItem, MallConstants.ORDER_TIME_OUT_0);
        }
        // 超时未付由 OrderAutoCancelJob 扫描关单；事件供 AFTER_COMMIT 监听方扩展
        applicationEventPublisher.publishEvent(new OrderCreatedEvent(this, orderInfo, MallConstants.ORDER_TIME_OUT_0, listOrderItem));
        return orderInfo;
    }

    @Override
    @Transactional(timeout = ORDER_TX_TIMEOUT_SECONDS, rollbackFor = Exception.class)
    public List<OrderInfo> orderAddMap(HttpServletRequest request, Map<String, PlaceOrderDTO> mapPlaceOrderDTO) throws Exception {
        String tenantId = TenantContextHolder.getTenantId();
        List<OrderInfo> rs = new ArrayList<>();
        try {
            // 按店铺 id 升序处理（店内再按 skuId 升序扣），全局加锁顺序一致，两单不会互等成死锁；返回仍按提交顺序
            List<Map.Entry<String, PlaceOrderDTO>> shops = new ArrayList<>(mapPlaceOrderDTO.entrySet());
            Map<Map.Entry<String, PlaceOrderDTO>, OrderInfo> placed = new IdentityHashMap<>();
            for (Map.Entry<String, PlaceOrderDTO> entry : stockDeduction().lockOrder(shops)) {
                PlaceOrderDTO placeOrderDTO = entry.getValue();
                placeOrderDTO.setShopId(entry.getKey());
                placed.put(entry, this.orderAddOne(request, placeOrderDTO));
            }
            shops.forEach(entry -> rs.add(placed.get(entry)));
        }
        catch (Exception | Error e) {
            BizMetrics.orderCreate(tenantId, BizMetrics.orderOutcomeOf(e));
            throw e;
        }
        countOrderCreatedOnCommit(tenantId);
        return rs;
    }

    private OrderStockDeduction stockDeduction() {
        return orderStockDeduction != null ? orderStockDeduction : OrderStockDeduction.DISABLED;
    }

    /** 下单成功按<b>提交结果</b>计数：方法体跑完不等于落库成功（提交时还可能因约束冲突回滚）。 */
    private static void countOrderCreatedOnCommit(String tenantId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            BizMetrics.orderCreate(tenantId, BizMetrics.OUTCOME_SUCCESS);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                BizMetrics.orderCreate(tenantId, status == STATUS_COMMITTED
                        ? BizMetrics.OUTCOME_SUCCESS : BizMetrics.OUTCOME_ERROR);
            }
        });
    }

    // ────────────────────────────── 支付入账 ──────────────────────────────

    /**
     * 订单入账：所有入账路径（回调、补单、结果页探查、取消前查单）都汇到这里。持订单锁，与取消互斥。
     */
    @Override
    @Transactional(timeout = 5, rollbackFor = Exception.class)
    @DistributedLock(name = "order", keys = {"#orderInfo.getId()"}, lockTimeoutStrategy = LockTimeoutStrategy.FAIL_FAST)
    public void notifyOrder(OrderInfo orderInfo) {
        // 锁内重查 DB 最新支付状态作幂等判据，不用调用方锁外的陈旧快照
        OrderInfo dbOrder = baseMapper.selectById(orderInfo.getId());
        if (dbOrder == null) {
            throw new BizException("订单不存在：" + orderInfo.getId());
        }
        if (!CommonConstants.NO.equals(dbOrder.getIsPay())) {
            // 已付订单又收到一笔确认到账：渠道或流水号不同就是「另一笔钱」，入差错池（DUP_PAY）
            PayExceptionRecorder.Result dup = paymentFactRecorder.secondPaymentIfAny(dbOrder, orderInfo);
            if (dup != null && !dup.persisted()) {
                // 事实只剩日志：让消息重投，别把这笔钱吞掉
                throw new BizException("重复到账入差错池失败，稍后重试");
            }
            log.info("订单 {} 已支付，支付回调幂等跳过{}", orderInfo.getOrderNo(), dup == null ? "" : "；另一笔到账已入差错池=" + dup);
            return;
        }
        // 已取消的订单不因「迟到的支付成功」复活（库存已回退），钱写差错池 LATE_PAID 待人工原路退
        if (OrderInfoEnum.STATUS_5.getValue().equals(dbOrder.getStatus())) {
            PayExceptionRecorder.Result late = paymentFactRecorder.latePaid(dbOrder, orderInfo, "NOTIFY_ORDER");
            log.error("【差错池】已取消订单收到支付成功，拒绝复活入账 orderNo={}, orderId={}, 入池={}",
                    dbOrder.getOrderNo(), dbOrder.getId(), late);
            if (!late.persisted()) {
                throw new BizException("迟到到账入差错池失败，稍后重试");
            }
            return;
        }
        List<OrderItem> listOrderItem = orderItemService.list(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderId, orderInfo.getId()));
        // 更新销量：按 SPU 汇总后原子累加
        listOrderItem.stream()
                .collect(Collectors.groupingBy(OrderItem::getSpuId, Collectors.summingInt(OrderItem::getQuantity)))
                .forEach(goodsSpuService::increaseSaleNum);
        // 状态写入只走状态机（PAY_SUCCESS → 待发货）：锁内重读、按原状态条件更新、写流水与 outbox 事件。
        // 放行失败必须抛异常整体回滚，否则销量已累加而订单没推进
        Transition<OrderState> paid = orderStateMachine.fire(orderInfo.getId(), OrderEvent.PAY_SUCCESS,
                OrderTransitionContext.builder()
                        .operatorType(Operator.CHANNEL)
                        .requestId(orderInfo.getChannelCallbackLogId())
                        .patch(OrderPatch.builder()
                                .paymentTime(orderInfo.getPaymentTime())
                                .transactionId(orderInfo.getTransactionId())
                                .paymentType(orderInfo.getPaymentType())
                                .tradeType(orderInfo.getTradeType())
                                .build())
                        .build());
        if (!paid.isOk()) {
            log.warn("订单 {} 支付成功未能推进（{}：{}），本次回调整体回滚", orderInfo.getOrderNo(), paid.outcome(), paid.reason());
            throw new BizException("订单支付状态已变更，请勿重复回调");
        }
        OrderStateSync.apply(orderInfo, paid.to());
        // 支付成功时一次写入商品项货款实付
        orderItemMapper.fillPaidAmount(orderInfo.getId());
        // 库存预占 HOLD → CONFIRMED（同一事务）
        if (stockHoldService != null) {
            stockHoldService.confirm(orderInfo.getId());
        }
        saveOrderRecord(orderInfo.getId(), OrderRecordTypeEnum.PAY, OptSourceEnum.USER);
        applicationEventPublisher.publishEvent(new OrderPaidEvent(this, orderInfo, listOrderItem));
    }

    @Override
    public void saveOrderRecord(String orderId, OrderRecordTypeEnum recordType, OptSourceEnum recordSource) {
        if (orderRecordMapper.selectCount(new LambdaQueryWrapper<OrderRecord>()
                .eq(OrderRecord::getOrderId, orderId)
                .eq(OrderRecord::getRecordType, recordType.name())) == 0) {
            insertOrderRecord(orderId, recordType, recordSource);
        }
    }

    /** 直接写一条订单记录，不查重：只给「这条记录不可能已存在」的调用方用（建单时的 CREATE）。 */
    private void insertOrderRecord(String orderId, OrderRecordTypeEnum recordType, OptSourceEnum recordSource) {
        OrderRecord orderRecord = new OrderRecord();
        orderRecord.setOrderId(orderId);
        orderRecord.setRecordType(recordType.name());
        orderRecord.setRecordSource(OptSourceEnum.defaultSource(recordSource).name());
        orderRecord.setRecordContent(recordType.getValue());
        orderRecordMapper.insert(orderRecord);
    }

    /**
     * 控制器已核过归属的收货地址（{@link PlaceOrderDTO#getResolvedShippingAddress()}）属于下单人、且就是请求里那个 id，就复用；
     * 否则（直接调 orderAddOne 的路径、DTO 里的地址对不上）照旧 {@link #requireOwnShippingAddress} 查库核验。
     */
    private UserAddress resolvedShippingAddressOrLoad(PlaceOrderDTO placeOrderDTO) {
        UserAddress resolved = placeOrderDTO.getResolvedShippingAddress();
        if (resolved != null
                && StringUtils.isNotBlank(placeOrderDTO.getUserId())
                && placeOrderDTO.getUserId().equals(resolved.getUserId())
                && resolved.getId() != null
                && resolved.getId().equals(placeOrderDTO.getUserAddressId())) {
            return resolved;
        }
        return requireOwnShippingAddress(placeOrderDTO);
    }
}
