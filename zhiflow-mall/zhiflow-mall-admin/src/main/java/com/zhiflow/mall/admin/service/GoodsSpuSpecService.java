/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.common.entity.GoodsSpuSpec;
import com.zhiflow.mall.common.entity.GoodsSpec;

import java.util.List;

/**
 * spu规格
 *
 * @date 2019-08-13 16:56:46
 */
public interface GoodsSpuSpecService extends IService<GoodsSpuSpec> {

	List<GoodsSpec> tree(GoodsSpuSpec goodsSpuSpec);
}
