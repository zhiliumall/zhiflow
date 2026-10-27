/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.common.entity.GoodsSpec;
import com.zhiflow.mall.common.entity.GoodsSpuSpec;

import java.util.List;

/**
 * spu规格
 *
 * @date 2019-08-13 16:56:46
 */
public interface GoodsSpuSpecMapper extends BaseMapper<GoodsSpuSpec> {

	List<GoodsSpec> selectTree(String spuId);
}
