/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import com.zhiflow.common.core.util.IdCodec;
import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.api.mapper.CardSpecMapper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品卡片规格接口(独立新增,不改动现有 controller/service)。
 * 前端拿到商品列表后批量补充规格;此接口失败仅影响规格展示,不影响商品主链路。
 *
 */
@RestController
@RequestMapping("/goodsspu")
public class CardSpecController {

	private final CardSpecMapper cardSpecMapper;
	private final IdCodec idCodec;

	public CardSpecController(CardSpecMapper cardSpecMapper, IdCodec idCodec) {
		this.cardSpecMapper = cardSpecMapper;
		this.idCodec = idCodec;
	}

	/**
	 * 批量查询 SPU 规格。入参为前端商品 id(可能是 hashid 加密串),
	 * 返回 { 原始id: 规格文案 },前端按 item.id 直接取用。
	 */
	@PostMapping("/sellSpecByIds")
	public R sellSpecByIds(@RequestBody List<String> spuIds) {
		Map<String, String> result = new HashMap<>();
		if (spuIds == null || spuIds.isEmpty()) {
			return R.ok(result);
		}
		// 解码为真实 id 查询;记住 真实id -> 原始id,便于把结果回填成前端认识的 key
		Map<String, String> realToOriginal = new HashMap<>();
		List<String> realIds = new ArrayList<>();
		for (String id : spuIds) {
			if (id == null) {
				continue;
			}
			String realId = idCodec.isHashid(id) ? idCodec.decode(id) : id;
			if (realId == null) {
				continue;
			}
			realToOriginal.put(realId, id);
			realIds.add(realId);
		}
		if (realIds.isEmpty()) {
			return R.ok(result);
		}
		List<Map<String, Object>> rows = cardSpecMapper.selectSellSpecBySpuIds(realIds);
		if (rows != null) {
			for (Map<String, Object> row : rows) {
				Object spuId = row.get("spu_id");
				Object specText = row.get("spec_text");
				if (spuId != null && specText != null) {
					String originalId = realToOriginal.get(String.valueOf(spuId));
					if (originalId != null) {
						result.put(originalId, String.valueOf(specText));
					}
				}
			}
		}
		return R.ok(result);
	}
}
