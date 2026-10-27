/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.common.entity.UserAddress;
import com.zhiflow.mall.admin.service.UserAddressService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 用户收货地址
 *
 * @date 2019-09-11 14:28:59
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/useraddress")
@Tag(description = "useraddress", name = "用户收货地址管理")
public class UserAddressController {

    private final UserAddressService userAddressService;

    /**
    * 分页查询
    * @param page 分页对象
    * @param userAddress 用户收货地址
    * @return
    */
	@Operation(summary = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@ato.hasAuthority('mall:useraddress:index')")
    public R getUserAddressPage(Page page, UserAddress userAddress) {
        return R.ok(userAddressService.page(page,Wrappers.query(userAddress)));
    }

    /**
    * 通过id查询用户收货地址
    * @param id
    * @return R
    */
	@Operation(summary = "通过id查询用户收货地址")
    @GetMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:useraddress:get')")
    public R getById(@PathVariable("id") String id){
        return R.ok(userAddressService.getById(id));
    }
}
