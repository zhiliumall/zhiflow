package com.zhiflow.mall.api.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import com.zhiflow.mall.api.service.OrderInfoService;
import com.zhiflow.mall.api.service.OrderRecordService;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.constant.MallReturnCode;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 订单记录
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/orderRecord")
@Tag(description = "orderRecord", name = "订单记录api")
public class OrderRecordController {

    private OrderRecordService orderRecordService;
    private final OrderInfoService orderInfoService;

    /**
     * 获取订单的状态记录
     *
     * <p>此前只要登录、不校验归属，任何登录用户按订单 id 可读别人订单的操作时间线。
     * 现按订单归属校验：订单不存在仍回空列表（原行为），不属于本人回 ERR_70001，口径同 {@code OrderInfoController#getById}。
     */
    @Operation(summary = "订单记录查询")
    @GetMapping("/{orderId}")
    @ApiLogin
    public R<List<OrderRecord>> getByOrderId(@PathVariable("orderId")String orderId) {
        OrderInfo order = orderInfoService.getById(orderId);
        if (order == null) {
            return R.ok(new ArrayList<>());
        }
        String currentUserId = ThirdSessionHolder.getUserId();
        if (currentUserId == null || !currentUserId.equals(order.getUserId())) {
            return R.failed(MallReturnCode.ERR_70001.getCode(), "无权访问此订单");
        }
        List<OrderRecord> list = orderRecordService.list(
                Wrappers.lambdaQuery(OrderRecord.class).eq(OrderRecord::getOrderId, orderId)
                        .eq(OrderRecord::getDelFlag, MallConstants.SHOW)
                        .orderByDesc(OrderRecord::getCreateTime));
        return R.ok(list);
    }

    /**
     * 批量获取订单的状态记录
     *
     * <p>与单条接口同一个洞：此前按请求里的订单 id 原样查，任何登录用户可批量读别人订单的时间线。
     * 现只查本人的订单；不属于本人 / 不存在的 id 仍占一个 key、值为空列表——
     * 返回形状与原来「没有日志的订单」完全一样，本人正常调用看不出区别，也不借此暴露哪些 id 存在。
     * @param orderIds 订单ID列表
     * @return Map格式，key为订单ID，value为该订单的日志列表
     */
    @Operation(summary = "批量查询订单记录", description = "根据订单ID列表批量查询订单日志，返回Map格式，key为订单ID，value为该订单的日志列表")
    @PostMapping("/batch")
    @ApiLogin
    public R<Map<String, List<OrderRecord>>> batchGetByOrderIds(@RequestBody List<String> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return R.ok(new HashMap<>());
        }

        String currentUserId = ThirdSessionHolder.getUserId();
        Set<String> ownedOrderIds = currentUserId == null ? Set.of() : orderInfoService.listByIds(orderIds).stream()
                .filter(o -> currentUserId.equals(o.getUserId()))
                .map(OrderInfo::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, List<OrderRecord>> result = new HashMap<>();
        if (!ownedOrderIds.isEmpty()) {
            // 批量查询本人订单的日志
            List<OrderRecord> allRecords = orderRecordService.list(
                    Wrappers.lambdaQuery(OrderRecord.class)
                            .in(OrderRecord::getOrderId, ownedOrderIds)
                            .eq(OrderRecord::getDelFlag, MallConstants.SHOW)
                            .orderByDesc(OrderRecord::getCreateTime));
            // 按订单ID分组
            result.putAll(allRecords.stream()
                    .collect(Collectors.groupingBy(OrderRecord::getOrderId)));
        }

        // 确保所有订单ID都有对应的key（即使没有日志也返回空列表）
        for (String orderId : orderIds) {
            result.putIfAbsent(orderId, new ArrayList<>());
        }

        return R.ok(result);
    }
}
