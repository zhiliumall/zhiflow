/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.lock.ratelimit.RateLimit;
import com.zhiflow.mall.api.payment.dto.PaymentContext;
import com.zhiflow.mall.common.enums.PayBizTypeEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.util.ClientIpResolver;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.common.portal.entity.ThirdSession;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import com.zhiflow.common.security.annotation.Inside;
import com.zhiflow.mall.api.mapper.OrderStateLogMapper;
import com.zhiflow.mall.api.service.*;
import com.zhiflow.mall.api.trade.StockBusyException;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.constant.MallReturnCode;
import com.zhiflow.mall.common.dto.PlaceOrderDTO;
import com.zhiflow.mall.common.dto.OrderMutationStatus;
import com.zhiflow.mall.common.entity.*;
import com.zhiflow.mall.common.enums.OptSourceEnum;
import com.zhiflow.mall.common.enums.OrderInfoEnum;
import com.zhiflow.mall.common.enums.PaymentTypeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.core.metadata.IPage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商城订单
 *
 * @date 2019-09-10 15:21:22
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/orderinfo")
@Tag(description = "orderinfo", name = "商城订单API")
public class OrderInfoController {

    private final OrderInfoService orderInfoService;
    private final GoodsSpuService goodsSpuService;
    private final ShopInfoService shopInfoService;
    private final OrderStateLogMapper orderStateLogMapper;
    /** 支付渠道注册表：新渠道只实现 {@code PaymentChannel} 并注册进来，{@code unifiedOrder} 一行不用改。 */
    private final com.zhiflow.mall.api.payment.PaymentChannelRegistry paymentChannelRegistry;
    private final com.zhiflow.mall.api.payment.PaymentService paymentService;

    /** 支付结果查询 */
    private final com.zhiflow.mall.api.trade.PayStatusService payStatusService;
    /** 下单防重 token */
    private final com.zhiflow.mall.api.trade.CheckoutTokenService checkoutTokenService;

    /**
     * 分页查询
     *
     * @param page      分页对象
     * @param orderInfo 商城订单
     * @return
     */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    @ApiLogin
    public R getPage(Page page, OrderInfo orderInfo) {
        orderInfo.setUserId(ThirdSessionHolder.getUserId());
        IPage<OrderInfo> result = orderInfoService.page2(page, orderInfo);
        // 列表同样下发每张单的操作（批量计算，不额外查库）
        if (result != null && result.getRecords() != null) {
            result.getRecords().forEach(OrderInfoController::fillUserActions);
        }
        return R.ok(result);
    }

    /** 买家侧操作（14.2）：商品项按已查出的 listOrderItem 判「还有没有可申请售后的」。 */
    static void fillUserActions(OrderInfo o) {
        // 逻辑已原样搬到 trade/UserOrderActions，与收银台聚合共用同一口径
        com.zhiflow.mall.api.trade.UserOrderActions.fill(o);
    }

    /**
     * 支付结果查询：收银台、结果页、失败页一律以它为准，前端不自己判成功 / 失败。
     *
     * <p>只能查自己的订单：别人的单 / 不存在一律 HTTP 404（不回 403，不泄露订单存在性）。
     * {@code probe=true} 时顺带向渠道探查（异步、5 秒去重、按渠道限流），接口立即返回当前状态；
     * 下次查询间隔看 {@code nextCheckAfterMs}（0 = 停止轮询，显示「支付结果确认中」）。
     */
    @Operation(summary = "支付结果查询")
    @GetMapping("/{id}/pay-status")
    @ApiLogin
    public org.springframework.http.ResponseEntity<R<com.zhiflow.mall.api.trade.PayStatusService.PayStatus>> payStatus(
            @PathVariable("id") String id, @RequestParam(value = "probe", required = false, defaultValue = "false") boolean probe,
            @RequestParam(value = "elapsedMs", required = false) Long elapsedMs) {
        String currentUserId = ThirdSessionHolder.getUserId();
        OrderInfo owned = orderInfoService.getById(id);
        if (owned == null || !currentUserId.equals(owned.getUserId())) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                    .body(R.failed(MallReturnCode.ERR_70005.getCode(), MallReturnCode.ERR_70005.getMsg()));
        }
        OrderInfo order = orderInfoService.getById2(id);
        if (order == null || !currentUserId.equals(order.getUserId())) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                    .body(R.failed(MallReturnCode.ERR_70005.getCode(), MallReturnCode.ERR_70005.getMsg()));
        }
        return org.springframework.http.ResponseEntity.ok(R.ok(payStatusService.query(order, probe, elapsedMs,
                LocalDateTime.now())));
    }

    /**
     * 通过id查询商城订单
     *
     * @param id
     * @return R
     */
    @Operation(summary = "通过id查询商城订单")
    @GetMapping("/{id}")
    @ApiLogin
    public R getById(@PathVariable("id") String id) {
        String currentUserId = ThirdSessionHolder.getUserId();
        OrderInfo owned = orderInfoService.getById(id);
        if (owned == null) {
            return R.ok(null);
        }
        if (!currentUserId.equals(owned.getUserId())) {
            return R.failed(MallReturnCode.ERR_70001.getCode(), "无权访问此订单");
        }
        OrderInfo orderInfo = orderInfoService.getById2(id);
        if (orderInfo == null) {
            return R.ok(null);
        }
        if (!currentUserId.equals(orderInfo.getUserId())) {
            return R.failed(MallReturnCode.ERR_70001.getCode(), "无权访问此订单");
        }
        fillUserActions(orderInfo);
        return R.ok(orderInfo);
    }

    /**
     * 新增订单集合（据店铺ID分组）
     *
     * @param mapPlaceOrderDTO 订单集合，已根据店铺分组
     * @return R
     */
    @Operation(summary = "新增订单集合（据店铺ID分组）")
    @RateLimit(name = "order-create", by = RateLimit.Dimension.USER, permits = 20, periodSeconds = 60, message = "下单太频繁，请稍后再试")
    @PostMapping("/map")
    @ApiLogin
    public R orderAddMap(HttpServletRequest request, @RequestBody Map<String, PlaceOrderDTO> mapPlaceOrderDTO) throws Exception {
        String userId = ThirdSessionHolder.getUserId();
        // 下单人一律取会话：请求体带别人的 userId 也不能以别人的身份建单（读别人的收货地址）。
        // 收货地址归属也在这里先核一遍：放在防重 token 认领之前，拒单不留任何写。
        try {
            for (PlaceOrderDTO dto : mapPlaceOrderDTO.values()) {
                if (dto != null) {
                    dto.setUserId(userId);
                    // 核过的地址随 DTO 带给 orderAddOne 复用，下单事务里不再查第二次（10-22）
                    dto.setResolvedShippingAddress(orderInfoService.requireOwnShippingAddress(dto));
                }
            }
        }
        catch (BizException e) {
            return R.failed(e.getMessage());
        }
        // 下单防重 token（双击 / 重试 / 重发不产生第二张单，重复提交返回原订单）
        String token = CheckoutTokenController.token(request);
        boolean holding = false;
        if (token == null) {
            if (checkoutTokenService.required()) {
                CheckoutTokenController.status(400);
                return R.failed("结算页已过期，请返回重新提交");
            }
            log.info("下单未带防重 token（灰度期放行）userId={}", userId);
        }
        else {
            com.zhiflow.mall.api.trade.CheckoutTokenService.Claim claim = checkoutTokenService.begin(userId, token);
            switch (claim.verdict()) {
                case IN_PROGRESS -> {
                    CheckoutTokenController.status(409);
                    return R.failed("订单正在提交，请勿重复点击");
                }
                case DONE -> {
                    List<OrderInfo> done = orderInfoService.listByIds(claim.doneOrderIds()).stream()
                            .filter(o -> userId.equals(o.getUserId())).toList();
                    log.info("下单防重：同一 token 重复提交，返回原订单 userId={} orders={}", userId, claim.doneOrderIds());
                    return R.ok(done);
                }
                case MISSING -> {
                    if (checkoutTokenService.required()) {
                        CheckoutTokenController.status(400);
                        return R.failed("结算页已过期，请返回重新提交");
                    }
                    log.info("下单防重 token 不存在或已过期（灰度期放行）userId={}", userId);
                }
                default -> holding = true;
            }
        }
        List<OrderInfo> listOrderInfo;
        try {
            listOrderInfo = orderInfoService.orderAddMap(request, mapPlaceOrderDTO);
        }
        catch (Exception e) {
            if (holding) {
                checkoutTokenService.release(userId, token);
            }
            // 热点商品抢不到行锁：整单已回滚、token 已放回，回业务码 70008 让前端提示稍后重提
            if (e instanceof StockBusyException) {
                return R.failed(StockBusyException.CODE.getCode(), StockBusyException.CODE.getMsg());
            }
            throw e;
        }
        if (CollectionUtil.isEmpty(listOrderInfo)) {
            if (holding) {
                checkoutTokenService.release(userId, token);
            }
            return R.failed(MallReturnCode.ERR_70003.getCode(), MallReturnCode.ERR_70003.getMsg());
        }
        if (holding) {
            checkoutTokenService.complete(userId, token, listOrderInfo.stream().map(OrderInfo::getId).toList());
        }
        return R.ok(listOrderInfo);
    }

    /**
     * 通过id删除商城订单
     *
     * @param id
     * @return R
     */
    @Operation(summary = "通过id删除商城订单")
    @DeleteMapping("/{id}")
    @ApiLogin
    public R removeById(@PathVariable String id) {
        OrderInfo orderInfo = orderInfoService.getById(id);
        if (orderInfo == null) {
            return R.failed(MallReturnCode.ERR_70005.getCode(), MallReturnCode.ERR_70005.getMsg());
        }
        //校验订单归属：orderId 是可枚举的雪花 ID，只判状态等于可删任意「已关闭且未支付」订单
        String currentUserId = ThirdSessionHolder.getUserId();
        if (!currentUserId.equals(orderInfo.getUserId())) {
            return R.failed(MallReturnCode.ERR_70001.getCode(), "无权操作此订单");
        }
        if (!OrderInfoEnum.STATUS_5.getValue().equals(orderInfo.getStatus()) || CommonConstants.YES.equals(orderInfo.getIsPay())) {
            return R.failed(MallReturnCode.ERR_70001.getCode(), MallReturnCode.ERR_70001.getMsg());
        }
        return R.ok(orderInfoService.removeById(id));
    }

    /**
     * 取消商城订单
     *
     * @param id 商城订单
     * @return R
     */
    @Operation(summary = "取消商城订单")
    @PutMapping("/cancel/{id}")
    @ApiLogin
    public R orderCancel(@PathVariable String id) {
        OrderInfo orderInfo = orderInfoService.getById(id);
        if (orderInfo == null) {
            return R.failed(MallReturnCode.ERR_70005.getCode(), MallReturnCode.ERR_70005.getMsg());
        }
        //校验订单归属。下面 orderCancelInside 是后台取消的服务间入口，走 SELLER 口径，不受此校验影响
        String currentUserId = ThirdSessionHolder.getUserId();
        if (!currentUserId.equals(orderInfo.getUserId())) {
            return R.failed(MallReturnCode.ERR_70001.getCode(), "无权操作此订单");
        }
        if (!CommonConstants.NO.equals(orderInfo.getIsPay())) {//只有未支付订单能取消
            return R.failed(MallReturnCode.ERR_70001.getCode(), MallReturnCode.ERR_70001.getMsg());
        }
        orderInfoService.orderCancelV2(orderInfo.getId(), OptSourceEnum.USER);
        return R.ok();
    }

    /**
     * 取消商城订单（服务间调用）
     *
     * @param id 商城订单
     * @return R
     */
    @Operation(summary = "取消商城订单（服务间调用）")
    @PutMapping("/inside/cancel/{id}")
    @Inside
    public R<Void> orderCancelInside(@PathVariable String id) {
        OrderInfo orderInfo = orderInfoService.getById(id);
        if (orderInfo == null) {
            return R.failed(MallReturnCode.ERR_70005.getCode(), MallReturnCode.ERR_70005.getMsg());
        }
        if (!CommonConstants.NO.equals(orderInfo.getIsPay())) {//只有未支付订单能取消
            return R.failed(MallReturnCode.ERR_70001.getCode(), MallReturnCode.ERR_70001.getMsg());
        }
        orderInfoService.orderCancel(orderInfo, OptSourceEnum.SELLER);
        return R.ok();
    }

    /**
     * 商城订单确认收货
     *
     * @param id 商城订单
     * @return R
     */
    @Operation(summary = "商城订单确认收货")
    @PutMapping("/receive/{id}")
    @ApiLogin
    public R orderReceive(@PathVariable String id) {
        OrderInfo orderInfo = orderInfoService.getById(id);
        if (orderInfo == null) {
            return R.failed(MallReturnCode.ERR_70005.getCode(), MallReturnCode.ERR_70005.getMsg());
        }
        //校验订单归属：确认收货会推进订单终态
        String currentUserId = ThirdSessionHolder.getUserId();
        if (!currentUserId.equals(orderInfo.getUserId())) {
            return R.failed(MallReturnCode.ERR_70001.getCode(), "无权操作此订单");
        }
        if (!OrderInfoEnum.STATUS_2.getValue().equals(orderInfo.getStatus())) {//只有待收货订单能确认收货
            return R.failed(MallReturnCode.ERR_70001.getCode(), MallReturnCode.ERR_70001.getMsg());
        }
        orderInfoService.orderReceive(orderInfo, OptSourceEnum.USER);
        return R.ok();
    }

    /**
     * 订单取消命令的只读恢复查询。只看状态机流水，不执行任何写操作。
     */
    @Operation(summary = "查询订单变更命令状态（服务间调用）")
    @GetMapping("/inside/mutation-status/{id}")
    @Inside
    public R<OrderMutationStatus> mutationStatus(@PathVariable String id,
            @RequestParam String operationId) {
        if (StrUtil.isBlank(id) || StrUtil.isBlank(operationId)) {
            return R.failed("订单号和 operationId 不能为空");
        }
        if (!operationId.equals("order-cancel:" + id)) {
            return R.failed("operationId 与订单不匹配");
        }
        String action = "CANCEL";

        OrderInfo order = orderInfoService.getById(id);
        if (order == null) {
            return R.ok(new OrderMutationStatus(id, operationId, action, "NOT_FOUND", null, false, true));
        }
        OrderStateLog log = orderStateLogMapper.selectByOrderAndOperation(id, operationId);
        com.zhiflow.mall.common.trade.statemachine.order.OrderState state =
                com.zhiflow.mall.common.trade.statemachine.order.OrderState.of(order.getStatus(), order.getIsPay());
        return R.ok(new OrderMutationStatus(id, operationId, action, state.name(), log == null ? null : log.getEvent(),
                log != null && action.equals(log.getEvent()), log != null));
    }

    /**
     * 发起支付：落支付单 → 交给渠道 → 返回前端下一步动作（{@code next_action}）。
     *
     * <p>金额一律取订单上服务端算好的实付金额，不接受客户端传值；入账只认渠道回调（{@code /paynotify}）。
     *
     * @param orderInfo 只读 id、paymentType、appType、appId、payOpenId、returnUrl、quitUrl
     */
    @Operation(summary = "发起支付")
    @RateLimit(name = "pay", by = RateLimit.Dimension.USER, permits = 30, periodSeconds = 60, message = "支付请求太频繁，请稍后再试")
    @PostMapping("/unifiedOrder")
    @ApiLogin
    public R unifiedOrder(HttpServletRequest request, @RequestBody OrderInfo orderInfo) {
        String returnUrl = orderInfo.getReturnUrl();
        String quitUrl = orderInfo.getQuitUrl();
        String paymentType = orderInfo.getPaymentType();
        String appId = orderInfo.getAppId();
        String payOpenId = orderInfo.getPayOpenId();
        // 支付运行环境（H5-WX / H5）来自本次支付请求，reload 前先捕获，避免被库里建单时的 appType 覆盖
        String reqAppType = orderInfo.getAppType();
        ThirdSession thirdSession = ThirdSessionHolder.getThirdSession();
        orderInfo = orderInfoService.getById(orderInfo.getId());
        if (orderInfo == null) {
            return R.failed(MallReturnCode.ERR_70005.getCode(), MallReturnCode.ERR_70005.getMsg());
        }
        // 校验订单归属：不校验的话，登录用户可替任意人的未付订单发起支付
        String currentUserId = ThirdSessionHolder.getUserId();
        if (currentUserId == null || !currentUserId.equals(orderInfo.getUserId())) {
            return R.failed(MallReturnCode.ERR_70001.getCode(), "无权操作此订单");
        }
        if (!CommonConstants.NO.equals(orderInfo.getIsPay())) {//只有未支付的订单能发起支付
            return R.failed(MallReturnCode.ERR_70004.getCode(), MallReturnCode.ERR_70004.getMsg());
        }
        // 已取消订单的支付成功不再入账（只能人工原路退款），从源头就不给已取消订单发起支付
        if (OrderInfoEnum.STATUS_5.getValue().equals(orderInfo.getStatus())) {
            return R.failed(MallReturnCode.ERR_70001.getCode(), "订单已取消，无法支付");
        }
        if (orderInfo.getPaymentPrice().compareTo(BigDecimal.ZERO) == 0) {//0元购买不调支付
            orderInfo.setPaymentTime(LocalDateTime.now());
            orderInfoService.notifyOrder(orderInfo);
            return R.ok();
        }
        if (StrUtil.isBlank(paymentType) || !paymentChannelRegistry.supports(paymentType)) {
            return R.failed(MallReturnCode.ERR_70002.getCode(), MallReturnCode.ERR_70002.getMsg());
        }
        // 微信内 JSAPI 必须有 openid：缺了返回独立错误码，前端据此走网页授权再重试（而不是渠道层的通用失败）
        String resolvedOpenId = StrUtil.isNotBlank(payOpenId) ? payOpenId : (thirdSession != null ? thirdSession.getOpenId() : null);
        if (PaymentTypeEnum.WECHAT.getValue().equals(paymentType) && "H5-WX".equals(reqAppType) && StrUtil.isBlank(resolvedOpenId)) {
            return R.failed(MallReturnCode.ERR_70009.getCode(), MallReturnCode.ERR_70009.getMsg());
        }
        // 用条件更新落库 payment_type：orderInfo 是上面读出的快照，期间支付回调可能已落地，
        // 加 eq(isPay, NO) 让「已支付」的订单影响 0 行，不会被改回未支付
        boolean typeSaved = orderInfoService.update(Wrappers.<OrderInfo>lambdaUpdate()
                .set(OrderInfo::getPaymentType, paymentType)
                .eq(OrderInfo::getId, orderInfo.getId())
                .eq(OrderInfo::getIsPay, CommonConstants.NO));
        if (!typeSaved) {
            log.warn("落库 payment_type 影响 0 行（订单可能已在本次请求期间支付）orderNo={}", orderInfo.getOrderNo());
        }
        ShopInfo shopInfo = shopInfoService.getById(orderInfo.getShopId());
        String shopName = StrUtil.maxLength(StrUtil.blankToDefault(shopInfo == null ? null : shopInfo.getName(), "商城"), 32);
        return paymentService.createPaymentAsR(PaymentContext.builder()
                .bizType(PayBizTypeEnum.ORDER.getValue())
                .bizId(orderInfo.getId())
                .orderNo(orderInfo.getOrderNo())
                .channelCode(paymentType)
                .amount(orderInfo.getPaymentPrice())
                .currCode("CNY")
                .subject(shopName + "-" + orderInfo.getOrderNo())
                .tenantId(orderInfo.getTenantId())
                .userId(currentUserId)
                .appType(StrUtil.blankToDefault(reqAppType, "H5"))
                .appId(StrUtil.isNotBlank(appId) ? appId : (thirdSession != null ? thirdSession.getAppId() : null))
                .payOpenId(resolvedOpenId)
                .returnUrl(returnUrl)
                .quitUrl(quitUrl)
                .clientIp(ClientIpResolver.forPayment(request, request::getRemoteAddr))
                .build());
    }

    /**
     * 统计当前用户各个状态的订单数（个人中心角标）。
     *
     * @return R
     */
    @Operation(summary = "统计各个状态订单计数")
    @GetMapping("/countAll")
    @ApiLogin
    public R count() {
        String userId = ThirdSessionHolder.getUserId();
        Map<String, Long> countAll = new HashMap<>();
        countAll.put(OrderInfoEnum.STATUS_0.getValue(), orderInfoService.count(Wrappers.<OrderInfo>lambdaQuery().eq(OrderInfo::getUserId, userId)
                .isNull(OrderInfo::getStatus)
                .eq(OrderInfo::getIsPay, CommonConstants.NO)));

        countAll.put(OrderInfoEnum.STATUS_1.getValue(), orderInfoService.count(Wrappers.<OrderInfo>lambdaQuery().eq(OrderInfo::getUserId, userId)
                .eq(OrderInfo::getStatus, OrderInfoEnum.STATUS_1.getValue())
                .eq(OrderInfo::getIsPay, CommonConstants.YES)));

        countAll.put(OrderInfoEnum.STATUS_2.getValue(), orderInfoService.count(Wrappers.<OrderInfo>lambdaQuery().eq(OrderInfo::getUserId, userId)
                .eq(OrderInfo::getStatus, OrderInfoEnum.STATUS_2.getValue())
                .eq(OrderInfo::getIsPay, CommonConstants.YES)));

        countAll.put(OrderInfoEnum.STATUS_3.getValue(), orderInfoService.count(Wrappers.<OrderInfo>lambdaQuery().eq(OrderInfo::getUserId, userId)
                .eq(OrderInfo::getStatus, OrderInfoEnum.STATUS_3.getValue())
                .eq(OrderInfo::getIsPay, CommonConstants.YES)));
        return R.ok(countAll);
    }
}
