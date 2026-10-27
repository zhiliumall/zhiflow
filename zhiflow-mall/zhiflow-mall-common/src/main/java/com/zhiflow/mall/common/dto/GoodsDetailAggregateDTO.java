/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.dto;

import com.zhiflow.mall.common.entity.*;
import com.zhiflow.mall.common.vo.GoodsSpecVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 商品详情页聚合数据：一次返回商品主数据、规格树、店铺信息，减少前端请求次数。
 *
 * @date 2025-01-22
 */
@Data
@Schema(description = "商品详情页聚合数据")
public class GoodsDetailAggregateDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 商品主数据（包含价格、图片、描述等）
     */
    @Schema(description = "商品主数据")
    private GoodsSpu goodsSpu;

    /**
     * 商品规格树（规格选项）
     */
    @Schema(description = "商品规格树")
    private List<GoodsSpecVO> specTree;

    /**
     * 店铺信息
     */
    @Schema(description = "店铺信息")
    private ShopInfo shopInfo;
}
