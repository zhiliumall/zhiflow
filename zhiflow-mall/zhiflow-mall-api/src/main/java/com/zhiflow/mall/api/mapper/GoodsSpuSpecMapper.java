/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.common.entity.GoodsSpuSpec;
import com.zhiflow.mall.common.vo.GoodsSpecVO;

import java.util.List;

/**
 * spu规格
 *
 * @date 2019-08-13 16:56:46
 */
public interface GoodsSpuSpecMapper extends BaseMapper<GoodsSpuSpec> {

	/** 规格树的各维度（不含取值，取值见 GoodsSkuSpecValueMapper#listSpecLeafBySpuId）。 */
	List<GoodsSpecVO> selectTree(String spuId);
}
