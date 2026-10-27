/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.core.util.IdCodec;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.mall.api.service.ShoppingCartService;
import com.zhiflow.mall.common.entity.ShoppingCart;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 购物车
 *
 * @date 2019-08-29 21:27:33
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/shoppingcart")
@Tag(description = "shoppingcart", name = "购物车API")
public class ShoppingCartController{

    private final ShoppingCartService shoppingCartService;
    private final IdCodec idCodec;

    private String decodeId(String id) {
        if (id == null) return null;
        return idCodec.isSnowflake(id) ? id : idCodec.decode(id);
    }

	/**
	 * 查询走缓存不能分页
	 * @return
	 */
	@Operation(summary = "查询走缓存不能分页")
    @GetMapping("/page")
	@ApiLogin
    public R getShoppingCartPage(ShoppingCart shoppingCart) {
		shoppingCart.setUserId(ThirdSessionHolder.getUserId());
		List<ShoppingCart> listShoppingCart = shoppingCartService.listCart(shoppingCart);
		return R.ok(new Page().setRecords(listShoppingCart).setTotal(listShoppingCart.size()));
    }

	/**
	 * 查询数量
	 * 性能优化：直接查询数据库 COUNT，不加载关联数据和活动信息
	 * 注意：返回的是数据库中未删除的购物车记录数（可能包含未上架商品等）
	 * @return
	 */
	@Operation(summary = "查询数量")
	@GetMapping("/count")
	public R getShoppingCartCount(ShoppingCart shoppingCart) {
		String userId = ThirdSessionHolder.getUserId();
		if(StrUtil.isBlank(userId)){
			return R.ok(0);
		}
		shoppingCart.setUserId(userId);
		// 性能优化：直接使用数据库 COUNT 查询，避免加载关联数据
		int count = shoppingCartService.countByCondition(shoppingCart);
		return R.ok(count);
	}

	/**
	 * 加入购物车
	 * @param shoppingCart
	 * @return
	 */
	@Operation(summary = "加入购物车")
	@PostMapping
	@ApiLogin
	public R save(@RequestBody ShoppingCart shoppingCart){
		requireAddFields(shoppingCart);
		shoppingCart.setUserId(ThirdSessionHolder.getUserId());
		shoppingCart.setSpuId(decodeId(shoppingCart.getSpuId()));
		shoppingCart.setSkuId(decodeId(shoppingCart.getSkuId()));
		return R.ok(shoppingCartService.save(shoppingCart));
	}

	/**
	 * 加购必填字段校验。
	 *
	 * <p>缺 {@code skuId} 的请求会一路走到 insert，撞 {@code shopping_cart} 的
	 * NOT NULL 约束报 500。缺字段是调用方的错，按业务错误返回（HTTP 200 + 失败码）。
	 * {@code shopId} 不要求前端传（单店铺）：为空时落默认店铺。
	 *
	 * <p>空 body 由 Spring 报文解析挡下（400），{@code {}} 能过解析进到这里——两条路径都要覆盖。
	 */
	static void requireAddFields(ShoppingCart cart) {
		if (StrUtil.isBlank(cart.getSpuId()) || StrUtil.isBlank(cart.getSkuId())) {
			throw new BizException("请选择商品规格后再加入购物车");
		}
		if (StrUtil.isBlank(cart.getShopId())) {
			cart.setShopId(CommonConstants.DEFAULT_SHOP_ID);
		}
		if (cart.getQuantity() == null || cart.getQuantity() < 1) {
			throw new BizException("购买数量不正确");
		}
	}

	/**
	 * 修改购物车商品
	 * @param shoppingCart
	 * @return
	 */
	@Operation(summary = "修改购物车商品")
	@PutMapping
	@ApiLogin
	public R edit(@RequestBody ShoppingCart shoppingCart){
		shoppingCart.setUserId(ThirdSessionHolder.getUserId());
		shoppingCart.setSpuId(decodeId(shoppingCart.getSpuId()));
		shoppingCart.setSkuId(decodeId(shoppingCart.getSkuId()));
		return R.ok(shoppingCartService.updateById(shoppingCart));
	}

	/**
	 * 删除购物车商品数量
	 * @param ids
	 * @return
	 */
	@Operation(summary = "删除购物车商品数量")
	@ApiLogin
	@PostMapping("/del")
	public R del(@RequestBody List<String> ids){
		if (ids == null || ids.isEmpty()) {
			return R.ok(Boolean.TRUE);
		}
		// 带 userId 的条件删除：只能删自己的购物车行，一次往返且天然免疫 TOCTOU。
		// remove(Wrapper) 在 ShoppingCartServiceImpl 里被覆盖为物理删。
		return R.ok(shoppingCartService.remove(Wrappers.lambdaQuery(ShoppingCart.class)
				.eq(ShoppingCart::getUserId, ThirdSessionHolder.getUserId())
				.in(ShoppingCart::getId, ids)));
	}
}
