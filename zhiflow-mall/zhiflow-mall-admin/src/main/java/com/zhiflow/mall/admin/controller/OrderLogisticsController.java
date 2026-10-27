/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.mall.admin.service.OrderLogisticsService;
import com.zhiflow.mall.common.entity.OrderLogistics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 订单物流
 *
 * @date 2019-09-16 09:53:17
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/orderlogistics")
@Tag(description = "orderlogistics", name = "订单物流管理")
public class OrderLogisticsController {

    private final OrderLogisticsService orderLogisticsService;

    /**
    * 分页查询
    * @param page 分页对象
    * @param orderLogistics 订单物流
    * @return
    */
	@Operation(summary = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@ato.hasAuthority('mall:orderlogistics:index','mall:orderinfo:index')")
    public R getOrderLogisticsPage(Page page, OrderLogistics orderLogistics) {
        return R.ok(orderLogisticsService.page(page,Wrappers.query(orderLogistics)));
    }

    /**
    * 通过id查询订单物流
    * @param id
    * @return R
    */
	@Operation(summary = "通过id查询订单物流")
    @GetMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:orderlogistics:get')")
    public R getById(@PathVariable("id") String id){
        return R.ok(orderLogisticsService.getById(id));
    }

    /**
    * 新增订单物流
    * @param orderLogistics 订单物流
    * @return R
    */
	@Operation(summary = "新增订单物流")
    @SysLog("新增订单物流")
    @PostMapping
    @PreAuthorize("@ato.hasAuthority('mall:orderlogistics:add')")
    public R save(@RequestBody OrderLogistics orderLogistics){
        return R.ok(orderLogisticsService.save(orderLogistics));
    }

    /**
    * 修改订单物流
    * @param orderLogistics 订单物流
    * @return R
    */
	@Operation(summary = "修改订单物流")
    @SysLog("修改订单物流")
    @PutMapping
    @PreAuthorize("@ato.hasAuthority('mall:orderlogistics:edit')")
    public R updateById(@RequestBody OrderLogistics orderLogistics){
        return R.ok(orderLogisticsService.updateById(orderLogistics));
    }

    /**
    * 通过id删除订单物流
    * @param id
    * @return R
    */
	@Operation(summary = "通过id删除订单物流")
    @SysLog("删除订单物流")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:orderlogistics:del')")
    public R removeById(@PathVariable String id){
        return R.ok(orderLogisticsService.removeById(id));
    }
}
