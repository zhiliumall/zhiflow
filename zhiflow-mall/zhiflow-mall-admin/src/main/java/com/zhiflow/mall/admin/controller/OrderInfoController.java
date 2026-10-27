/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.controller;

import com.zhiflow.mall.admin.support.FileDownloads;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.api.mall.OrderInfoApi;
import com.zhiflow.api.weixin.WxAppApi;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.BatchResult;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.common.security.annotation.Inside;
import com.zhiflow.common.security.util.SecurityUtils;
import com.zhiflow.mall.admin.service.*;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.dto.GoodsSummaryVO;
import com.zhiflow.mall.common.dto.OrderDeliveryDTO;
import com.zhiflow.mall.common.dto.OrderStatiscisDTO;
import com.zhiflow.mall.common.dto.OrderSummaryVO;
import com.zhiflow.mall.common.dto.OrderRemarkBatchRequest;
import com.zhiflow.mall.common.dto.OrderRemarkBatchResult;
import com.zhiflow.mall.common.entity.*;
import com.zhiflow.mall.common.enums.OptSourceEnum;
import com.zhiflow.mall.common.enums.OrderInfoEnum;
import com.zhiflow.api.weixin.dto.WxAppInsideDTO;
import com.zhiflow.mall.admin.support.WxContractDisplayMapper;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商城订单
 *
 * @date 2019-09-10 15:21:22
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/orderinfo")
@Tag(description = "orderinfo", name = "商城订单管理")
public class OrderInfoController {

    private final OrderInfoService orderInfoService;
    /** 支付状态机 v1.2 阶段 1：审核两种跳转走状态机（O6 / 人工处理），不再直接条件更新 status */
    private final com.zhiflow.mall.common.trade.statemachine.order.OrderStateMachine orderStateMachine;
	private final UserInfoService userInfoService;
	private final WxAppApi wxAppHttpClient;
	private final OrderInfoApi orderInfoApi;
	private final OrderItemService orderItemService;
	private final OrderRecordService orderRecordService;
	private final OrderLogisticsService orderLogisticsService;
	private final OrderExportService orderExportService;

	private static final String ORDER_NOT_FOUND = "订单不存在";

	/** 订单 id 为空或订单不存在：调用方统一回「订单不存在」。 */
	private boolean notFound(String orderId) {
		return StrUtil.isBlank(orderId) || orderInfoService.getById(orderId) == null;
	}

    /**
    * 分页查询
    * @param page 分页对象
    * @param orderInfo 商城订单
    * @return
    */
	@Operation(summary = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@ato.hasAuthority('mall:orderinfo:index')")
    public R getPage(Page page, OrderInfo orderInfo) {
        return R.ok(orderInfoService.page1(page, Wrappers.query(orderInfo)));
    }

	/**
	 * 查询数量
	 * @param orderInfo
	 * @return
	 */
	@Operation(summary = "查询数量")
	@GetMapping("/count")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:count')")
	public R getCount(OrderInfo orderInfo) {
		return R.ok(orderInfoService.count(Wrappers.query(orderInfo)));
	}

    /**
    * 通过id查询商城订单
    * @param id
    * @return R
    */
	@Operation(summary = "通过id查询商城订单")
    @GetMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:orderinfo:get')")
    public R getById(@PathVariable("id") String id){
		OrderInfo orderInfo = orderInfoService.getById1(id);
		if (orderInfo == null) {
			return R.failed(ORDER_NOT_FOUND);
		}
		// app_type 允许为空（后台建单 / 历史数据），原来裸 .equals 同样 NPE
		if(MallConstants.CLIENT_TYPE_MA.equals(orderInfo.getAppType()) ||
				MallConstants.CLIENT_TYPE_H5_WX.equals(orderInfo.getAppType())){
			R<WxAppInsideDTO> r2 = wxAppHttpClient.getById(orderInfo.getAppId(), SecurityConstants.FROM_IN);
			orderInfo.setApp(WxContractDisplayMapper.toDisplayEntity(r2.getData()));
		}
		orderInfo.setUserInfo(userInfoService.getById(orderInfo.getUserId()));

		List<OrderRecord> orderRecordList = orderRecordService.list(Wrappers.<OrderRecord>query().lambda().eq(OrderRecord::getOrderId, orderInfo.getId()).orderByAsc(OrderRecord::getCreateTime));
		orderRecordList.forEach(t -> t.setRecordSource(OptSourceEnum.getByName(t.getRecordSource()).getValue()));
		orderInfo.setListOrderRecord(orderRecordList);
		OrderLogistics one = orderLogisticsService.getOne(Wrappers.lambdaQuery(OrderLogistics.class).eq(OrderLogistics::getOrderId, id));
		JSONObject jsonObject = new JSONObject();
		jsonObject.put("logistic",one);
		jsonObject.put("orderInfo",orderInfo);
		// 状态机眼里的状态名；LEGACY_INVALID 时前端显示「人工处理」入口（PUT /{id}/manual-fix）
		jsonObject.put("machineState", com.zhiflow.mall.common.trade.statemachine.order.OrderState
				.of(orderInfo.getStatus(), orderInfo.getIsPay()).name());
		// 后台订单详情下发可做的操作（由跳转表算出，含置灰原因）；按钮一律以此为准
		jsonObject.put("actions", com.zhiflow.mall.common.trade.statemachine.order.OrderActionResolver.resolve(
				orderInfo, com.zhiflow.mall.common.trade.statemachine.order.OrderActionResolver.Side.ADMIN,
				new com.zhiflow.mall.common.trade.statemachine.order.OrderActionResolver.Facts(null),
				java.time.LocalDateTime.now()));
		return R.ok(jsonObject);
    }

	/**
	 * 查询统计付款金额
	 * @param orderInfo
	 * @return R
	 */
	@Operation(summary = "查询统计付款金额")
	@GetMapping("/paymentprice/sum")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:get')")
	public R sumPaymentPrice(OrderInfo orderInfo){
		return R.ok(orderInfoService.sumPaymentPrice(orderInfo));
	}

    /**
    * 修改商城订单
    * @param orderInfo 商城订单
    * @return R
    */
	@Operation(summary = "修改商城订单")
    @SysLog("修改商城订单")
    @PutMapping
    @PreAuthorize("@ato.hasAuthority('mall:orderinfo:edit')")
    public R updateById(@RequestBody OrderInfo orderInfo){
        // 只写收货信息；订单状态只能经发货、取消等业务操作改变，带 status 的请求一律拒绝
        if (StrUtil.isBlank(orderInfo.getId()) || notFound(orderInfo.getId())) {
            return R.failed(ORDER_NOT_FOUND);
        }
        if (orderInfo.getStatus() != null) {
            return R.failed("订单状态请通过对应的业务操作修改（发货、取消等）");
        }
        return R.ok(updateEditableFields(orderInfo));
    }

    private static com.zhiflow.mall.common.trade.statemachine.order.OrderTransitionContext auditCtx(String reason, com.zhiflow.mall.common.trade.statemachine.order.OrderState manualTarget) {
        var user = SecurityUtils.getUser();
        return com.zhiflow.mall.common.trade.statemachine.order.OrderTransitionContext.builder()
                .operatorType(com.zhiflow.mall.common.trade.statemachine.Operator.ADMIN)
                .operatorId(user == null || user.getId() == null ? null : String.valueOf(user.getId()))
                .reason(reason)
                .manualTarget(manualTarget)
                .build();
    }

    /**
     * 通用修改接口允许写的字段：收货信息。
     * 只拷这几个字段到一个新实体再写，请求里的 is_pay / 金额 / 状态等一律不会落库。
     *
     * @return 是否写库；没有任何可写字段时返回 false 且不写
     */
    private boolean updateEditableFields(OrderInfo req) {
        OrderInfo patch = new OrderInfo();
        patch.setId(req.getId());
        patch.setRecName(req.getRecName());
        patch.setRecTel(req.getRecTel());
        patch.setRecAddress(req.getRecAddress());
        if (patch.getRecName() == null && patch.getRecTel() == null && patch.getRecAddress() == null) {
            log.warn("修改商城订单：没有可修改的字段（收货信息），其余字段不经此接口修改，已忽略 orderId={}",
                    req.getId());
            return false;
        }
        return orderInfoService.updateById(patch);
    }

    /**
    * 通过id删除商城订单
    * @param id
    * @return R
    */
	@Operation(summary = "通过id删除商城订单")
    @SysLog("删除商城订单")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:orderinfo:del')")
    public R removeById(@PathVariable String id){
        if (notFound(id)) {
            return R.failed(ORDER_NOT_FOUND);
        }
        return R.ok(orderInfoService.removeById(id));
    }

	/**
	 * 订单状态人工处理（规则 M1）。
	 *
	 * <p>只对存量非法组合（{@code LEGACY_INVALID}：表外的 status + is_pay 组合）开放：选一个合法状态并必填原因，
	 * 由状态机在锁内条件更新、写流水（operator_type=ADMIN，reason 带变更单号）。合法状态的订单一律拒绝——
	 * 它们只能走正常事件（发货、取消等），不能被人工「改状态」。
	 */
	@Operation(summary = "订单状态人工处理（仅存量非法状态）")
	@SysLog("订单状态人工处理")
	@PutMapping("/{id}/manual-fix")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:edit')")
	public R manualFix(@PathVariable String id, @RequestBody ManualFixRequest req) {
		if (notFound(id)) {
			return R.failed(ORDER_NOT_FOUND);
		}
		if (req == null || StrUtil.isBlank(req.targetState()) || StrUtil.isBlank(req.reason())) {
			return R.failed("请选择目标状态并填写原因（含变更单号）");
		}
		com.zhiflow.mall.common.trade.statemachine.order.OrderState target;
		try {
			target = com.zhiflow.mall.common.trade.statemachine.order.OrderState.valueOf(req.targetState());
		}
		catch (IllegalArgumentException e) {
			return R.failed("未知的目标状态：" + req.targetState());
		}
		var t = orderStateMachine.fire(id, com.zhiflow.mall.common.trade.statemachine.order.OrderEvent.MANUAL_FIX,
				auditCtx(req.reason().trim(), target));
		if (!t.isOk()) {
			return R.failed("不能人工处理：" + t.reason());
		}
		return R.ok(Map.of("from", String.valueOf(t.from()), "to", String.valueOf(t.to()), "logId", String.valueOf(t.logId())));
	}

	/** 人工处理请求：目标状态取 OrderState 枚举名（如 PENDING_PAY / COMPLETED），原因必填。 */
	public record ManualFixRequest(String targetState, String reason) {
	}

	/**
	 * 取消商城订单
	 * @param id 商城订单
	 * @return R
	 */
	@Operation(summary = "取消商城订单")
	@SysLog("取消商城订单")
	@PutMapping("/cancel/{id}")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:edit')")
	public R orderCancel(@PathVariable String id){
		OrderInfo orderInfo = orderInfoService.getById(id);
		if (orderInfo == null) {
			return R.failed(ORDER_NOT_FOUND);
		}
		return cancelOrderWithRecovery(id);
	}

	private R<Void> cancelOrderWithRecovery(String id) {
		return com.zhiflow.mall.admin.service.OrderMutationRecovery.recover(
				this.orderInfoApi.orderCancelInside(id, SecurityConstants.FROM_IN), this.orderInfoApi, id,
				"order-cancel:" + id, SecurityConstants.FROM_IN);
	}

	/**
	 * 按列表的筛选条件导出订单（Excel，一单一行）。查询对象与 {@code /page} 同款，单次最多 1 万单。
	 *
	 * <p>权限取列表位：导出只读，能看列表就能导。
	 */
	@Operation(summary = "导出订单")
	@SysLog("导出订单")
	@GetMapping("/export")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:index')")
	public ResponseEntity<byte[]> export(OrderInfo orderInfo) {
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
		return FileDownloads.attachment(orderExportService.export(orderInfo), "订单导出_" + stamp + ".xlsx");
	}

	@Operation(summary = "批量发货模板")
	@GetMapping("/batch-delivery/template")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:edit')")
	public ResponseEntity<byte[]> batchDeliveryTemplate() {
		return FileDownloads.attachment(orderExportService.deliveryTemplate(), "批量发货模板.xlsx");
	}

	/**
	 * 上传 Excel 批量发货（订单号、物流公司、运单号）。逐行处理、逐行回结果，单行失败不影响其他行。
	 */
	@Operation(summary = "批量发货")
	@SysLog("批量发货")
	@PostMapping("/batch-delivery")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:edit')")
	public R<BatchResult<String>> batchDelivery(@RequestPart("file") MultipartFile file) {
		return R.ok(orderExportService.batchDelivery(file));
	}

	/**
	 * 发货商城订单
	 * @param orderDeliveryDTO 订单发货参数
	 * @return R
	 */
	@Operation(summary = "发货商城订单")
	@SysLog("发货商城订单")
	@PutMapping("/delivery")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:edit')")
	public R delivery(@RequestBody OrderDeliveryDTO orderDeliveryDTO){
		if (notFound(orderDeliveryDTO == null ? null : orderDeliveryDTO.getOrderId())) {
			return R.failed(ORDER_NOT_FOUND);
		}
		orderInfoService.delivery(orderDeliveryDTO);
		return R.ok();
	}

	/**
	 * 查询统计
	 * @param orderInfo
	 * @return
	 */
	@Operation(summary = "查询统计")
	@GetMapping("/statistics")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:statistics')")
	public R getStatistics(OrderInfo orderInfo) {
		//总数量
		long countTotal = orderInfoService.count(Wrappers.query(orderInfo));
		//今天数量
		LocalDate localDate = LocalDateTime.now().toLocalDate();
		long countToday = orderInfoService.count(Wrappers.query(orderInfo).between("create_time", LocalDateTime.of(localDate, LocalTime.MIN), LocalDateTime.of(localDate, LocalTime.MAX)));
		Map<String, Object> rs = new HashMap<>();
		rs.put("countTotal",countTotal);
		rs.put("countToday",countToday);
		return R.ok(rs);
	}

	/**
	 * 查询统计（根据某字段分组）
	 * @param orderInfo
	 * @return
	 */
	@Operation(summary = "查询统计（根据某字段分组）")
	@GetMapping("/statistics/{column}")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:statistics')")
	public R getStatisticsByColumn(@PathVariable("column") String column, OrderInfo orderInfo) {
		// 增加校验，仅允许字母、数字和下划线，防止非法字符或 SQL 注入
		if (!column.matches("^[a-zA-Z0-9_]+$")) {
			return R.failed("非法统计字段");
		}
		List<Map<String, Object>> appTypeMapList= orderInfoService.listMaps(Wrappers.query(orderInfo)
				.select(column + " as name, count(*) as value")
				.groupBy(column)
				.orderByAsc(column));
		return R.ok(appTypeMapList);
	}

	/**
	 * 批量统计各个状态的订单数量（优化版本）
	 * 使用 GROUP BY 一次性查询所有状态的订单数量，替代多次独立查询
	 * 
	 * @param shopId 店铺ID（可选）
	 * @return Map<状态, 数量>
	 */
	@Operation(summary = "批量统计各个状态的订单数量")
	@GetMapping("/bulkCount")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:statistics')")
	public R getBulkOrderCount(@RequestParam(required = false) String shopId) {
		return R.ok(orderInfoService.getOrderCountsByStatus(shopId));
	}

	/**
	 * 备注
	 */
	@Operation(summary = "备注")
	@SysLog("备注")
	@PutMapping("/remark")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:edit')")
	public R remark(@RequestBody OrderInfo orderInfo){
		if (notFound(orderInfo.getId())) {
			return R.failed(ORDER_NOT_FOUND);
		}
		OrderInfo oi = new OrderInfo();
		oi.setId(orderInfo.getId());
		oi.setRemark(orderInfo.getRemark());
		orderInfoService.updateById(oi);
		return R.ok();
	}

	@Operation(summary = "批量订单备注")
	@SysLog("批量订单备注")
	@PutMapping("/remark/batch")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:edit')")
	public R<OrderRemarkBatchResult> batchRemark(@RequestBody OrderRemarkBatchRequest request) {
		if (request == null || request.getOrderIds() == null) return R.failed("订单不能为空");
		List<String> orderIds = request.getOrderIds().stream().filter(java.util.Objects::nonNull).map(String::trim)
			.filter(id -> !id.isEmpty()).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)).stream().toList();
		if (orderIds.isEmpty() || orderIds.size() > 100) return R.failed("每次请选择1至100笔订单");
		if (orderIds.stream().anyMatch(id -> !id.matches("\\d{1,32}"))) return R.failed("订单ID格式非法");
		if (StrUtil.isBlank(request.getRemark()) || request.getRemark().length() > 500) return R.failed("备注不能为空且不能超过500个字符");
		request.setOrderIds(orderIds);
		return R.ok(orderInfoService.batchRemark(request, null));
	}

	/**
	 * 商家备注（V71 专列，与通用 remark 分离）。读取随 GET /orderinfo/{id} 返回。
	 */
	@Operation(summary = "商家备注")
	@SysLog("商家备注")
	@PutMapping("/merchantRemark")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:edit')")
	public R merchantRemark(@RequestBody OrderInfo orderInfo){
		if (notFound(orderInfo.getId())) {
			return R.failed(ORDER_NOT_FOUND);
		}
		OrderInfo oi = new OrderInfo();
		oi.setId(orderInfo.getId());
		oi.setMerchantRemark(orderInfo.getMerchantRemark());
		orderInfoService.updateById(oi);
		return R.ok();
	}
	/**
	 * 修改地址
	 */
	@Operation(summary = "修改地址")
	@SysLog("修改地址")
	@PutMapping("/address")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:edit')")
	public R address(@RequestBody OrderInfo orderInfo){
		if (notFound(orderInfo.getId())) {
			return R.failed(ORDER_NOT_FOUND);
		}
		OrderInfo oi = new OrderInfo();
		oi.setId(orderInfo.getId());
		oi.setRecName(orderInfo.getRecName());
		oi.setRecTel(orderInfo.getRecTel());
		oi.setRecAddress(orderInfo.getRecAddress());
		orderInfoService.updateById(oi);
		return R.ok();
	}

	//订单量统计  当日订单量   昨日订单量 当日订单/昨日订单 * 100
	@Operation(summary = "订单量统计")
	@GetMapping("/orderStatiscis")
	// [R6 批四·读 2026-08-18] 补方法级鉴权；订单统计，复用订单统计读码
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:statistics')")
	public R orderStatiscis() {
		//当日日期
		LocalDate now = LocalDate.now();
		long nowCount = orderInfoService.count(Wrappers.<OrderInfo>lambdaQuery().eq(OrderInfo::getIsPay, CommonConstants.YES)
				.ge(OrderInfo::getPaymentTime, now.atStartOfDay()).le(OrderInfo::getPaymentTime, now.atTime(23, 59, 59)));
		//昨日订单
		LocalDate yesterday = now.minusDays(1);
		long yesterdayCount = orderInfoService.count(Wrappers.<OrderInfo>lambdaQuery().eq(OrderInfo::getIsPay, CommonConstants.YES)
				.ge(OrderInfo::getPaymentTime, yesterday.atStartOfDay()).le(OrderInfo::getPaymentTime, yesterday.atTime(23, 59, 59)));

		// 计算日环比增长率（环比 = (今天 - 昨天) / 昨天 * 100%）
		double period = 0.0;
		if (yesterdayCount > 0) {
			period = ((double) (nowCount - yesterdayCount) / yesterdayCount) * 100;
		} else if (nowCount > 0) {
			period = 100.0; // 昨天为0，今天有数据，视为增长100%
		}
		OrderStatiscisDTO orderStatiscisDTO = new OrderStatiscisDTO();
		orderStatiscisDTO.setNowOrderCount(nowCount);
		orderStatiscisDTO.setYesOrderCount(yesterdayCount);
		orderStatiscisDTO.setPeriod("%.2f%%".formatted(period)); // 保留两位小数
		return R.ok(orderStatiscisDTO);
	}

	/**
	 * Restricted IM-to-mall lookup. expectedUserId is derived from an IM
	 * session after agent authorization; @Inside keeps it unreachable to a
	 * browser and the service verifies the order owner again.
	 */

	private OrderSummaryVO toOrderSummary(OrderInfo o) {
		OrderSummaryVO vo = new OrderSummaryVO();
		vo.setId(o.getId());
		vo.setOrderNo(o.getOrderNo());
		vo.setStatus(o.getStatus());
		vo.setStatusLabel(OrderInfoEnum.getDesc(o.getStatus()));
		vo.setPaymentWay(o.getPaymentWay());
		vo.setPaymentPrice(o.getPaymentPrice());
		vo.setFreightPrice(o.getFreightPrice());
		vo.setCreateTime(o.getCreateTime());
		vo.setPaymentTime(o.getPaymentTime());
		// 行为轨迹「取消订单」事件用：cancelTime 是 V19 新增列，历史订单为 null → 调用方拿 updateTime 兜底
		vo.setCancelTime(o.getCancelTime());
		vo.setUpdateTime(o.getUpdateTime());
		vo.setName(o.getName());
		return vo;
	}

	private void enrichOrderSummary(OrderSummaryVO summary, List<OrderItem> items) {
		if (items == null || items.isEmpty()) return;
		OrderItem firstItem = items.getFirst();
		summary.setMainImage(firstItem.getPicUrl());
		summary.setGoodsQty(items.stream()
			.map(OrderItem::getQuantity)
			.filter(java.util.Objects::nonNull)
			.mapToInt(Integer::intValue)
			.sum());
		if (StringUtils.isBlank(summary.getName())) {
			summary.setName(firstItem.getSpuName());
		}
	}

	private GoodsSummaryVO mapToGoodsSummary(Map m) {
		GoodsSummaryVO vo = new GoodsSummaryVO();
		Object id = m.get("id");
		if (id != null) vo.setSpuId(String.valueOf(id));
		vo.setName((String) m.get("name"));
		vo.setCategoryName((String) m.get("categoryname"));
		// PostgreSQL folds unquoted aliases to lower-case in Map keys.
		Object pics = m.get("picurls");
		if (pics != null) {
			String s = pics.toString();
			vo.setImage(parseFirstPicUrl(s));
		}
		Object price = m.get("pricedown");
		if (price instanceof java.math.BigDecimal bd) vo.setPrice(bd);
		else if (price != null) vo.setPrice(new java.math.BigDecimal(price.toString()));
		Object qty = m.get("quantity");
		if (qty != null) {
			try { vo.setPurchaseTimes(Integer.valueOf(qty.toString())); }
			catch (NumberFormatException ignore) { vo.setPurchaseTimes(null); }
		}
		return vo;
	}

	private String parseFirstPicUrl(String picUrlsJson) {
		if (picUrlsJson == null || picUrlsJson.isBlank()) return null;
		try {
			List<String> urls = JSONArray.parseArray(picUrlsJson, String.class);
			return urls == null || urls.isEmpty() ? null : urls.get(0);
		} catch (Exception e) {
			return null;
		}
	}
}
