package com.zhiflow.mall.api.controller;

import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.mall.common.entity.ShoppingCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 加购必填字段（默认店铺）：前端不传 shopId 时落默认店铺，规格与数量仍是必填。
 */
class ShoppingCartAddFieldsTest {

	private static ShoppingCart cart(String shopId) {
		ShoppingCart cart = new ShoppingCart();
		cart.setSpuId("spu-1");
		cart.setSkuId("sku-1");
		cart.setQuantity(1);
		cart.setShopId(shopId);
		return cart;
	}

	@Test
	@DisplayName("shopId 为空 → 落默认店铺")
	void blankShopIdFallsBackToDefaultShop() {
		ShoppingCart cart = cart(" ");
		ShoppingCartController.requireAddFields(cart);
		assertThat(cart.getShopId()).isEqualTo(CommonConstants.DEFAULT_SHOP_ID);
	}

	@Test
	@DisplayName("对照：传了 shopId 原样保留")
	void givenShopIdKept() {
		ShoppingCart cart = cart("7");
		ShoppingCartController.requireAddFields(cart);
		assertThat(cart.getShopId()).isEqualTo("7");
	}

	@Test
	@DisplayName("缺规格仍是业务错误")
	void missingSkuRejected() {
		ShoppingCart cart = cart(null);
		cart.setSkuId(null);
		assertThatThrownBy(() -> ShoppingCartController.requireAddFields(cart)).isInstanceOf(BizException.class);
	}
}
