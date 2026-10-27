/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.common.entity.GoodsSkuSpecValue;

import java.util.List;

/**
 * 商品sku规格值
 *
 * @date 2019-08-13 10:19:09
 */
public interface GoodsSkuSpecValueService extends IService<GoodsSkuSpecValue> {

    List<GoodsSkuSpecValue> listGoodsSkuSpecValueBySkuId(List<String> skuIds);
}
