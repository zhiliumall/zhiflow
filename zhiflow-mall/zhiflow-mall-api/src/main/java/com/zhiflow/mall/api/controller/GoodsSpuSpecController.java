/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import com.zhiflow.common.core.util.IdCodec;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.mall.api.service.GoodsSpuSpecService;
import com.zhiflow.mall.common.entity.GoodsSpuSpec;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * spu规格
 *
 * @date 2019-08-13 16:56:46
 */
@Slf4j
@ApiLogin(mustLogin = false)
@RestController
@AllArgsConstructor
@RequestMapping("/goodsspuspec")
@Tag(description = "goodsspuspec", name = "spu规格接口API")
public class GoodsSpuSpecController {

    private final GoodsSpuSpecService goodsSpuSpecService;
	private final IdCodec idCodec;


	/**
	 * 获取商品规格
	 * @param goodsSpuSpec
	 * @return
	 */
	@Operation(summary = "获取商品规格")
	@GetMapping("/tree")
	public R getGoodsSpuSpecTree(GoodsSpuSpec goodsSpuSpec) {
		// spuId 前端传的是 hashid 加密串，需先解码成真实主键
		String spuId = goodsSpuSpec.getSpuId();
		if (spuId != null && idCodec.isHashid(spuId)) {
			goodsSpuSpec.setSpuId(idCodec.decode(spuId));
		}
		return R.ok(goodsSpuSpecService.tree(goodsSpuSpec));
	}

}
