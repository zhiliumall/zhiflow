/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.common.entity.OrderItem;
import com.zhiflow.mall.admin.service.OrderItemService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 商城订单详情
 *
 * @date 2019-09-10 15:31:40
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/orderitem")
@Tag(description = "orderitem", name = "商城订单详情管理")
public class OrderItemController {

    private final OrderItemService orderItemService;

    /**
    * 分页查询
    * @param page 分页对象
    * @param orderItem 商城订单详情
    * @return
    */
	@Operation(summary = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@ato.hasAuthority('mall:orderitem:index')")
    public R getOrderItemPage(Page page, OrderItem orderItem) {
        return R.ok(orderItemService.page(page,Wrappers.query(orderItem)));
    }

    /**
    * 通过id查询商城订单详情
    * @param id
    * @return R
    */
	@Operation(summary = "通过id查询商城订单详情")
    @GetMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:orderinfo:get')")
    public R getById(@PathVariable("id") String id){
        return R.ok(orderItemService.getById2(id));
    }
}
