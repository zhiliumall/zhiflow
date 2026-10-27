/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.mall.common.entity.GoodsSkuSpecValue;
import com.zhiflow.mall.admin.mapper.GoodsSkuSpecValueMapper;
import com.zhiflow.mall.admin.service.GoodsSkuSpecValueService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 商品sku规格值
 *
 * @date 2019-08-13 10:19:09
 */
@Service
public class GoodsSkuSpecValueServiceImpl extends ServiceImpl<GoodsSkuSpecValueMapper, GoodsSkuSpecValue> implements GoodsSkuSpecValueService {

    @Override
    public List<GoodsSkuSpecValue> listGoodsSkuSpecValueBySkuId(List<String> skuIds) {
        return baseMapper.listGoodsSkuSpecValueBySkuId(skuIds);
    }
}
