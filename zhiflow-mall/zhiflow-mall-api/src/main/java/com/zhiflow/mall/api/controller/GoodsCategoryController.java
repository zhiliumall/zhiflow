/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.mall.api.cache.GoodsCategoryTreeSnapshot;
import com.zhiflow.mall.api.service.GoodsCategoryService;
import com.zhiflow.mall.api.service.GoodsSpuService;
import com.zhiflow.mall.common.entity.GoodsCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 商品类目
 *
 * @date 2019-08-12 11:46:28
 */
@Slf4j
@ApiLogin(mustLogin = false)
@RestController
@AllArgsConstructor
@RequestMapping("/goodscategory")
@Tag(description = "goodscategory", name = "商品类目API")
public class GoodsCategoryController {

    private final GoodsCategoryService goodsCategoryService;
    private final GoodsSpuService goodsSpuService;
    /**
    * 返回树形集合（支持 HTTP 缓存）
    *
    * <p>没带额外筛选参数时走服务端缓存（整棵启用的树）；带了参数（查询条件直接由请求参数拼出）照旧查库。
    */
	@Operation(summary = "返回树形集合")
    @GetMapping("/tree")
    public ResponseEntity<R> goodsCategoryTree(
			GoodsCategory goodsCategory,
			HttpServletRequest request,
			HttpServletResponse response) {
		goodsCategory.setEnable(CommonConstants.YES);
		List<?> categoryTree = isWholeTree(goodsCategory)
				? GoodsCategoryTreeSnapshot.toJson(goodsCategoryService.enabledTree())
				: goodsCategoryService.selectTreeOptimized(goodsCategory);
		// 生成 Etag（基于数据内容的哈希值）
		String etag = generateEtag(categoryTree);

		// 检查客户端是否提供了 If-None-Match 头
		String ifNoneMatch = request.getHeader("If-None-Match");
		if (StrUtil.isNotBlank(ifNoneMatch) && etag.equals(ifNoneMatch)) {
			// 数据未变化，返回 304
			return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
				.eTag(etag)
				.cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate())
				.build();
		}
		
		// 数据已变化，返回新数据和 Etag
		return ResponseEntity.ok()
			.eTag(etag)
			.cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate())
			.body(R.ok(categoryTree));
    }

	/** 除了固定的 enable=1，请求里没有绑定到任何筛选字段。 */
	static boolean isWholeTree(GoodsCategory query) {
		GoodsCategory whole = new GoodsCategory();
		whole.setEnable(CommonConstants.YES);
		return whole.equals(query);
	}

	/**
	 * 生成 Etag（基于数据内容的 MD5 哈希值）
	 */
	private String generateEtag(List<?> categoryTree) {
		try {
			return DigestUtil.md5Hex(com.alibaba.fastjson2.JSON.toJSONString(categoryTree));
		} catch (Exception e) {
			log.error("生成 Etag 失败", e);
			return String.valueOf(System.currentTimeMillis());
		}
	}

    /**
     * 抓取二级分类数据
     */
    @Operation(summary = "抓取二级分类数据")
    @GetMapping("/twoCategory")
    public R twoCategory(GoodsCategory goodsCategory) {
        goodsCategory.setEnable(CommonConstants.YES);
        return R.ok(goodsCategoryService.twoCategoryOptimized(goodsCategory));
    }

    /**
     * 二级分类商品数据
     */
    @Operation(summary = "二级分类商品数据")
    @GetMapping("/twoCategoryGoodSpu")
    public R twoCategorySpu(@RequestParam String id) {
        return R.ok(goodsSpuService.listCategorySpu(id));
    }
}
