/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.mall.api.mapper.GoodsSkuSpecValueMapper;
import com.zhiflow.mall.common.entity.GoodsSkuSpecValue;
import com.zhiflow.mall.common.entity.GoodsSpuSpec;
import com.zhiflow.mall.api.mapper.GoodsSpuSpecMapper;
import com.zhiflow.mall.api.service.GoodsSpuSpecService;
import com.zhiflow.mall.common.vo.GoodsSpecLeafVO;
import com.zhiflow.mall.common.vo.GoodsSpecVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * spu规格
 *
 * @date 2019-08-13 16:56:46
 */
@Service
@RequiredArgsConstructor
public class GoodsSpuSpecServiceImpl extends ServiceImpl<GoodsSpuSpecMapper, GoodsSpuSpec> implements GoodsSpuSpecService {

	private final GoodsSkuSpecValueMapper goodsSkuSpecValueMapper;

	/**
	 * 规格树：维度一条、全部维度的取值一条，共 2 条 SQL（原来每个维度再各查一条取值）。
	 * 没有取值的维度给空列表，与原来嵌套查询的结果一致。
	 */
	@Override
	public List<GoodsSpecVO> tree(GoodsSpuSpec goodsSpuSpec) {
		List<GoodsSpecVO> specs = baseMapper.selectTree(goodsSpuSpec.getSpuId());
		if (specs.isEmpty()) {
			return specs;
		}
		Map<String, List<GoodsSpecLeafVO>> leavesBySpec = new LinkedHashMap<>();
		for (GoodsSkuSpecValue value : goodsSkuSpecValueMapper.listSpecLeafBySpuId(goodsSpuSpec.getSpuId())) {
			GoodsSpecLeafVO leaf = new GoodsSpecLeafVO();
			leaf.setId(value.getSpecValueId());
			leaf.setValue(value.getSpecValueName());
			leavesBySpec.computeIfAbsent(value.getSpecId(), k -> new ArrayList<>()).add(leaf);
		}
		for (GoodsSpecVO spec : specs) {
			spec.setLeaf(new ArrayList<>(leavesBySpec.getOrDefault(spec.getId(), List.of())));
		}
		return specs;
	}
}
