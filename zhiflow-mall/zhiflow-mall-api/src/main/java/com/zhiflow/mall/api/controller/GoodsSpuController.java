/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.core.util.IdCodec;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.mall.api.service.GoodsSpuService;
import com.zhiflow.mall.common.constant.MallReturnCode;
import com.zhiflow.mall.common.dto.GoodsDetailAggregateDTO;
import com.zhiflow.mall.common.entity.GoodsSpu;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * spu商品
 *
 * @date 2019-08-12 16:25:10
 */
@RestController
@AllArgsConstructor
@RequestMapping("/goodsspu")
@Tag(description = "goodsspu", name = "商品接口API")
public class GoodsSpuController {

	private final GoodsSpuService goodsSpuService;
	private final IdCodec idCodec;

	private String decodeId(String id) {
		if (id == null) return null;
		return idCodec.isSnowflake(id) ? id : idCodec.decode(id);
	}

	/**
	 * 商品列表 / 搜索
	 * @param page 分页对象
	 * @param goodsSpu 条件：name（关键词）、categoryFirst/Second/Third、brandId
	 * @param tab 排序：default(综合) / sales / price_asc / price_desc / newest
	 * @return
	 */
	@Operation(summary = "商品列表 / 搜索")
	@ApiLogin(mustLogin = false)
	@GetMapping("/page")
	public R getGoodsSpuPage(Page page, GoodsSpu goodsSpu,
							 @RequestParam(required = false) BigDecimal minPrice,
							 @RequestParam(required = false) BigDecimal maxPrice,
							 @RequestParam(required = false) String tab) {
		return R.ok(goodsSpuService.page(page, goodsSpu, minPrice, maxPrice, tab));
	}

	/**
	 * 通过id查询spu商品
	 * @param id 商品ID
	 * @return 商品详情
	 */
	@Operation(summary = "通过id查询spu商品")
	@ApiLogin(mustLogin = false)
	@GetMapping("/{id}")
	public R getById(@PathVariable("id") String id) {
		String realId = decodeId(id);
		GoodsSpu goodsSpu = realId == null ? null : goodsSpuService.getById2Optimized(realId);
		if (goodsSpu == null) {
			return R.failed(MallReturnCode.ERR_80004.getCode(), MallReturnCode.ERR_80004.getMsg());
		}
		return R.ok(goodsSpu);
	}

	/**
	 * list查询通过ids
	 * @param ids ids
	 * @return
	 */
	@Operation(summary = "list查询通过ids")
	@ApiLogin(mustLogin = false)
	@PostMapping("/listbyids")
	public R getGoodsSpuListByIds(@RequestBody List<String> ids) {
		List<String> realIds = ids.stream()
				.map(id -> idCodec.isHashid(id) ? idCodec.decode(id) : id)
				.filter(Objects::nonNull)
				.toList();
		return R.ok(goodsSpuService.listGoodsSpuByIds(realIds));
	}

	/**
	 * 商品详情页聚合接口：一次返回商品主数据、规格树、店铺信息
	 * @param id 商品SPU ID
	 * @return 商品详情聚合数据
	 */
	@Operation(summary = "商品详情页聚合接口")
	@ApiLogin(mustLogin = false)
	@GetMapping("/detail/init/{id}")
	public R getGoodsDetailAggregate(@PathVariable("id") String id) {
		String realId = decodeId(id);
		GoodsDetailAggregateDTO aggregateData = realId == null ? null : goodsSpuService.getGoodsDetailAggregate(realId);
		if (aggregateData == null) {
			return R.failed(MallReturnCode.ERR_80004.getCode(), MallReturnCode.ERR_80004.getMsg());
		}
		return R.ok(aggregateData);
	}
}
