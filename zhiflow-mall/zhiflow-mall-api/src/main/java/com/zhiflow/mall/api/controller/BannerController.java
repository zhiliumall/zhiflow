package com.zhiflow.mall.api.controller;

import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.mall.api.service.BannerService;
import com.zhiflow.mall.common.entity.Banner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 首页轮播图。只返回图片、跳转地址与排序。
 */
@ApiLogin(mustLogin = false)
@RestController
@RequiredArgsConstructor
@RequestMapping("/banner")
@Tag(name = "首页轮播图")
public class BannerController {

	private final BannerService bannerService;

	@Operation(summary = "启用的轮播图")
	@GetMapping("/list")
	public R<List<Banner>> list() {
		return R.ok(bannerService.listEnabled());
	}
}
