/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import com.zhiflow.mall.api.service.OrderInfoService;
import com.zhiflow.mall.api.service.OrderItemService;
import com.zhiflow.mall.common.constant.MallReturnCode;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderItem;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商城订单详情
 *
 * @date 2019-09-10 15:31:40
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/orderitem")
@Tag(description = "orderitem", name = "商城订单详情API")
public class OrderItemController {

    private final OrderItemService orderItemService;
    private final OrderInfoService orderInfoService;

    /**
    * 通过id查询商城订单详情
    *
    * <p>返回买了什么、规格、数量、实付，属于用户隐私数据。此前是 {@code mustLogin = false} 且不校验归属，
    * 匿名按 id 即可读任意人的订单行。调用方只有 h5 / pc 的售后申请、售后详情页（都在登录态、都是本人订单），
    * 收紧为「必须登录 + 所属订单是本人的」。取不到仍回 {@code data:null}（前端偶发传 {@code undefined}，保持原样），
    * 不属于本人回 ERR_70001，口径同 {@code OrderInfoController#getById}。
    * @param id
    * @return R
    */
	@Operation(summary = "通过id查询商城订单详情")
    @ApiLogin
    @GetMapping("/{id}")
    public R getById(@PathVariable("id") String id){
        OrderItem orderItem = orderItemService.getById2(id);
        if (orderItem == null) {
            return R.ok(null);
        }
        String currentUserId = ThirdSessionHolder.getUserId();
        OrderInfo order = StrUtil.isBlank(orderItem.getOrderId()) ? null : orderInfoService.getById(orderItem.getOrderId());
        if (currentUserId == null || order == null || !currentUserId.equals(order.getUserId())) {
            return R.failed(MallReturnCode.ERR_70001.getCode(), "无权访问此订单");
        }
        return R.ok(orderItem);
    }

}
