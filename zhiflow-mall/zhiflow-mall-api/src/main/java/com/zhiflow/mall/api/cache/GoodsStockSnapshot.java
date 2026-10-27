/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.cache;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 商品详情里会随订单变的部分：各在售 SKU 的库存与版本号、商品销量。存在 {@code mall:goods_stock#5} 里，5 秒过期。
 *
 * <p>下单、支付、取消<b>都不清</b>它：热门商品每 5 秒最多回源一次，与访问量、下单量无关，不会被每一单击穿。
 * 代价是详情页展示的库存、销量最多晚 5 秒；下单校验走条件 UPDATE，永远以数据库为准，不读这里。
 * 后台改库存、改 SKU 时提交后清，立即生效。
 */
@Data
public class GoodsStockSnapshot implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private String spuId;
	private Integer saleNum;
	private List<SkuStock> skus;

	@Data
	public static class SkuStock implements Serializable {

		@Serial
		private static final long serialVersionUID = 1L;

		private String skuId;
		private Integer stock;
		/** 库存每变一次 +1（见 GoodsSkuMapper#decreaseStock），与库存一起变，所以放在这里而不是静态快照里。 */
		private Integer version;
	}
}
