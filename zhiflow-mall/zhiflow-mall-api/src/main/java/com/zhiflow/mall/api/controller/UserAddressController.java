/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.mall.api.service.UserAddressService;
import com.zhiflow.mall.common.entity.UserAddress;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户收货地址
 *
 * @date 2019-09-11 14:28:59
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/useraddress")
@Tag(description = "useraddress", name = "用户收货地址API")
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
	@ApiLogin
    public R getUserAddressPage(Page page, UserAddress userAddress) {
		userAddress.setUserId(ThirdSessionHolder.getUserId());
        return R.ok(userAddressService.page(page,Wrappers.query(userAddress)));
    }

    /**
    * 通过 id 查询本人的收货地址
    *
    * <p>不存在与不属于本人回同一个失败，不借此暴露别人的地址 id 是否存在。
    * @param id 地址 id
    * @return R
    */
	@Operation(summary = "通过id查询本人的收货地址")
    @GetMapping("/{id}")
	@ApiLogin
    public R getById(@PathVariable String id) {
		UserAddress userAddress = userAddressService.getById(id);
		if (userAddress == null || !ThirdSessionHolder.getUserId().equals(userAddress.getUserId())) {
			return R.failed("地址不存在");
		}
		return R.ok(userAddress);
    }

    /**
    * 新增、修改用户收货地址
    *
    * <p>带 id 时先核归属（与下面 removeById 同一口径）。原来直接 saveOrUpdate：请求体带别人地址的 id，
    * 就会按 {@code WHERE id=?} 覆盖那一行、并把 user_id 改成自己——等于把别人的地址夺走。
    * 库里没有这个 id 时按新增处理、主键由服务端生成（不让客户端指定主键）；user_id 一律取会话。
    * @param userAddress 用户收货地址
    * @return R
    */
	@Operation(summary = "新增、修改用户收货地址")
    @PostMapping
	@ApiLogin
    public R save(@RequestBody UserAddress userAddress){
		// user_address.area_id 是 NOT NULL 且无 DEFAULT：缺该字段原先直接撞库约束、
		// 被全局异常处理器兜成「服务异常，请稍后重试」，终端拿不到是哪个字段的问题。
		if (StrUtil.isBlank(userAddress.getAreaId())) {
			return R.failed("请选择所在地区");
		}
		String userId = ThirdSessionHolder.getUserId();
		if (StrUtil.isBlank(userAddress.getId())) {
			userAddress.setId(null);
		} else {
			UserAddress existing = userAddressService.getById(userAddress.getId());
			if (existing == null) {
				userAddress.setId(null);
			} else if (!userId.equals(existing.getUserId())) {
				return R.failed("无权操作");
			}
		}
		userAddress.setUserId(userId);
        return R.ok(userAddressService.saveOrUpdate(userAddress));
    }

    /**
    * 通过id删除用户收货地址
    * @param id
    * @return R
    */
	@Operation(summary = "通过id删除用户收货地址")
    @DeleteMapping("/{id}")
	@ApiLogin
    public R removeById(@PathVariable String id){
		UserAddress userAddress = userAddressService.getById(id);
		if(userAddress == null){
			return R.ok();
		}
		String userId = ThirdSessionHolder.getUserId();
		if(!userId.equals(userAddress.getUserId())){
			return R.failed("无权操作");
		}
		return R.ok(userAddressService.removeById(id));
    }

}
