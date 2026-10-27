/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.controller;

import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.admin.mapper.OrderInfoMapper;
import com.zhiflow.mall.admin.service.GlobalHotWordService;
import com.zhiflow.mall.admin.service.OrderInfoService;
import com.zhiflow.mall.admin.service.HomepageOverviewService;
import com.zhiflow.mall.admin.service.UserInfoService;
import com.zhiflow.mall.admin.statistics.BusinessMetricScopeResolver;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.enums.OrderInfoEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 大屏统计
 *
 * @date 2019-09-10 15:21:22
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/largescreen")
@Tag(description = "largescreen", name = "大屏统计")
public class LargeScreenController {

    private final OrderInfoService orderInfoService;
	private final OrderInfoMapper orderInfoMapper;
	private final GlobalHotWordService globalHotWordService;
	private final UserInfoService userInfoService;
	private final HomepageOverviewService homepageOverviewService;
	private final BusinessMetricScopeResolver businessMetricScopeResolver;
	private final java.util.concurrent.ExecutorService largeScreenExecutor;

	/** 待发货订单超过该小时数计入「即将超时」预警 */
	private static final long SHIP_TIMEOUT_HOURS = 24;

	/**
	 * 转化漏斗口径声明。随响应下发，避免前端把 visitors 当成「全站访问 UV」（走查 B18）。
	 */
	private static final String FUNNEL_DEFINITION =
			"同源漏斗，四步严格嵌套（每层是上一层的子集，转化率恒 ≤ 100%）："
			+ "visitors=当期有商品详情足迹的登录用户 → registered=其中当期新注册的 → "
			+ "ordered=其中当期下过单的 → paid=其中当期支付成功的（is_pay=1 且状态为待发货/待收货/已完成）。"
			+ "⚠ visitors 只覆盖登录用户的商品详情浏览，不含匿名访客与首页/列表页，非全站访问 UV；"
			+ "shopId 只作用于订单两层，足迹与注册没有店铺维度。";
	/**
	 * 首页经营快照。范围、业务时区和时间窗均由服务端解析，客户端不能选择店铺或时间。
	 */
	@Operation(summary = "首页经营概览")
	@GetMapping("/homepage-overview")
	@PreAuthorize("@ato.hasAuthority('mall:largescreen:statistics')")
	public R homepageOverview() {
		return R.ok(homepageOverviewService.overview(businessMetricScopeResolver.resolveCurrent()));
	}

	/** 把 [{name:'YYYY-MM-DD',...}] 聚合行按 name 建索引 */
	private Map<String, Map<String, Object>> indexByName(List<Map<String, Object>> rows) {
		Map<String, Map<String, Object>> byDate = new HashMap<>();
		if (rows != null) {
			for (Map<String, Object> row : rows) {
				Object name = row.get("name");
				if (name != null) {
					byDate.put(name.toString(), row);
				}
			}
		}
		return byDate;
	}

	/** 销售额汇总:isPay=1、status in (1,2,3)、create_time 落在 [begin,end)、按 shopId 过滤 */
	private BigDecimal sumSales(String shopId, boolean hasShop, LocalDateTime begin, LocalDateTime end) {
		OrderInfo query = new OrderInfo();
		if (hasShop) query.setShopId(shopId);
		query.setIsPay(CommonConstants.YES);
		List<String> statusList = new ArrayList<>();
		statusList.add(OrderInfoEnum.STATUS_1.getValue());
		statusList.add(OrderInfoEnum.STATUS_2.getValue());
		statusList.add(OrderInfoEnum.STATUS_3.getValue());
		query.setStatusList(statusList);
		query.setBeginTime(begin);
		query.setEndTime(end);
		BigDecimal value = orderInfoService.sumPaymentPrice(query);
		return value == null ? BigDecimal.ZERO : value;
	}
}
