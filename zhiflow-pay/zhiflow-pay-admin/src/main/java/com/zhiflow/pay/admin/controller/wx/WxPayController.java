/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.pay.admin.controller.wx;

import com.zhiflow.common.core.observability.ExtCallMetrics;
import com.github.binarywang.wxpay.bean.notify.WxPayOrderNotifyResult;
import com.github.binarywang.wxpay.bean.profitsharing.request.ProfitSharingRequest;
import com.github.binarywang.wxpay.bean.profitsharing.request.ProfitSharingUnfreezeRequest;
import com.github.binarywang.wxpay.bean.request.WxPayOrderQueryRequest;
import com.github.binarywang.wxpay.bean.request.WxPayUnifiedOrderRequest;
import com.github.binarywang.wxpay.bean.result.WxPayOrderQueryResult;
import com.github.binarywang.wxpay.config.WxPayConfig;
import com.github.binarywang.wxpay.exception.WxPayException;
import com.github.binarywang.wxpay.service.WxPayService;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.security.annotation.Inside;
import com.zhiflow.pay.admin.config.WxPayConfiguration;
import com.zhiflow.pay.admin.service.PayConfigService;
import com.zhiflow.pay.common.constant.PayConstants;
import com.zhiflow.pay.common.dto.inside.WxOrderNotifyDTO;
import com.zhiflow.pay.common.dto.inside.WxOrderQueryDTO;
import com.zhiflow.pay.common.dto.inside.WxOrderQueryReq;
import com.zhiflow.pay.common.dto.inside.WxProfitSharingFinishReq;
import com.zhiflow.pay.common.dto.inside.WxProfitSharingReq;
import com.zhiflow.pay.common.dto.inside.WxUnifiedOrderReq;
import com.zhiflow.pay.common.entity.PayConfig;
import tools.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 微信支付
 *
 * @date 2019-03-23 21:26:35
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/wxpay")
@Tag(description = "wxpay", name = "微信支付")
public class WxPayController {

	/** 外部依赖统一出口指标里的依赖名。 */
	static final String EXT_DEP = "wxpay";

	/** 微信支付 SDK 异常归类：带业务错误码 / return_code=FAIL 的是渠道拒绝（biz_reject），其余按超时 / 错误归类。 */
	static String classify(Throwable t) {
		if (t instanceof WxPayException e && ((e.getErrCode() != null && !e.getErrCode().isBlank())
				|| "FAIL".equalsIgnoreCase(e.getReturnCode()))) {
			return ExtCallMetrics.BIZ_REJECT;
		}
		return ExtCallMetrics.classify(t);
	}

	/**
	 * ⚠️ 本类每个接口在用到 type=1 收款配置之前都要先判空，没配置就回
	 * {@code R.failed(WxPayConfiguration.NOT_CONFIGURED)}（2026-09-29）。
	 *
	 * <p>生产上租户删了 type=1、店铺信息里却还留着微信商户号，mall-api 取消未付款单前照样来查单：
	 * 以前 {@code payConfig.getModel()} 直接 NPE，gRPC 下是「未映射的异常 → INTERNAL」，12 天 9 次。
	 * 用「返回」而不是「抛」：这些方法有 REST / gRPC / 单体进程内三条传输，返回值在三条路上形状一样；
	 * 异常在进程内那条路会直接冒进 mall-api 的取消流程。
	 */
	private final PayConfigService payConfigService;

	/**
	 * [B6] 与 MVC 同一个 ObjectMapper —— {@link #unifiedOrder} 要把统一下单结果序列化成
	 * <b>原始 JSON</b> 交给契约（见 {@code ZhiflowPayApi#unifiedOrderWx} 的注释）。
	 * 必须用容器里那个 bean 而不是自己 new 一个：自己 new 的配置与 MVC 不同，
	 * 吐给前端的字节就可能与今天不一样，而这正是本次迁移要保住的东西。
	 */
	private final ObjectMapper objectMapper;

	/**
	 * 调用统一下单接口，并组装生成支付所需参数对象.
	 *
	 * @param request 统一下单请求参数
	 * @return 返回 {@link com.github.binarywang.wxpay.bean.order}包下的类对象
	 */
	@Operation(summary = "调用统一下单接口")
	@Inside
	@PostMapping("/unifiedOrder")
	public R<String> unifiedOrder(@RequestBody WxUnifiedOrderReq req) {
		WxPayUnifiedOrderRequest request = toSdkRequest(req);
		PayConfig payConfig = payConfigService.findByType(PayConstants.PAY_TYPE_1);
		if (payConfig == null) {
			return R.failed(WxPayConfiguration.NOT_CONFIGURED);
		}
		WxPayConfig wxPayConfig = new WxPayConfig();
		wxPayConfig.setSubMchId(request.getSubMchId());
		WxPayService wxPayService = WxPayConfiguration.getPayService(wxPayConfig);
		try {
			if("NATIVE".equals(request.getTradeType())){//trade_type=NATIVE时
				request.setProductId(request.getOutTradeNo());
			}
			if(PayConstants.PAY_MODEL_2.equals(payConfig.getModel())){//普通模式
				if("JSAPI".equals(request.getTradeType())){//trade_type=JSAPI时（小程序、公众号h5）
					request.setAppid(request.getSubAppId());
					request.setOpenid(request.getSubOpenid());
				}
				request.setSubMchId(null);
				request.setSubAppId(null);
				request.setSubOpenid(null);
				wxPayConfig.setSubMchId(null);
				wxPayConfig.setSubAppId(null);
			}else{
				if("APP".equals(request.getTradeType())){//trade_type=APP时
					request.setSubAppId(request.getAppid());
				}
			}
			// 序列化成原始 JSON：按 tradeType 这里可能是 Map / MpOrderResult / NativeOrderResult /
			// AppOrderResult / MwebOrderResult 之一，没有单一类型可言。走 MVC 同一个 mapper，
			// 与今天 REST 下 R.data 的字节逐字相同。
			return R.ok(objectMapper.writeValueAsString(ExtCallMetrics.record(EXT_DEP, "create",
					() -> wxPayService.createOrder(request), null, WxPayController::classify)));
		} catch (WxPayException e) {
			return R.failed(e.getReturnMsg() + "" + e.getCustomErrorMsg() + "" + e.getErrCodeDes());
		}
	}

	/**
	 * [B6 契约治理] 请求投影 → SDK 请求对象。
	 *
	 * <p>字段是 5 个活调用点 {@code setXxx} 的并集（见 {@link WxUnifiedOrderReq}）。
	 * {@code productId} 不在这里设 —— 它由下面的原有分支按 tradeType 设置。
	 */
	static WxPayUnifiedOrderRequest toSdkRequest(WxUnifiedOrderReq req) {
		WxPayUnifiedOrderRequest request = new WxPayUnifiedOrderRequest();
		request.setAppid(req.getAppid());
		request.setSubAppId(req.getSubAppId());
		request.setSubMchId(req.getSubMchId());
		request.setSubOpenid(req.getSubOpenid());
		request.setBody(req.getBody());
		request.setOutTradeNo(req.getOutTradeNo());
		request.setTotalFee(req.getTotalFee());
		request.setTradeType(req.getTradeType());
		request.setNotifyUrl(req.getNotifyUrl());
		request.setSpbillCreateIp(req.getSpbillCreateIp());
		return request;
	}

	/**
	 * 处理支付回调
	 * @param xmlData
	 * @return
	 */
	@Operation(summary = "处理支付回调")
	@Inside
	@PostMapping("/notifyOrder")
	public R<WxOrderNotifyDTO> notifyOrder(@RequestBody String xmlData) {
		WxPayOrderNotifyResult rs = WxPayOrderNotifyResult.fromXML(xmlData);
//		String appId = rs.getAppid();
		String SubMchId = rs.getSubMchId();
		WxPayConfig wxPayConfig = new WxPayConfig();
//		wxPayConfig.setAppId(appId);
		wxPayConfig.setSubMchId(SubMchId);
		try {
			WxPayService wxPayService = WxPayConfiguration.getPayService(wxPayConfig);
			WxPayOrderNotifyResult notifyResult = wxPayService.parseOrderNotifyResult(xmlData);
			return R.ok(project(notifyResult));
		} catch (WxPayException e) {
			log.error(e.getMessage());
			return R.failed(e.getReturnMsg() + "" + e.getCustomErrorMsg() + "" + e.getErrCodeDes());
		} catch (Exception e) {
			log.error(e.getMessage());
			return R.failed(e.getMessage());
		}
	}

	/**
	 * 查询订单
	 * @param request
	 * @return
	 */
	@Operation(summary = "查询订单")
	@Inside
	@PostMapping("/queryOrder")
	public R<WxOrderQueryDTO> queryOrder(@RequestBody WxOrderQueryReq req) {
		WxPayOrderQueryRequest request = toSdkRequest(req);
		WxPayConfig wxPayConfig = new WxPayConfig();
		wxPayConfig.setAppId(request.getAppid());
		wxPayConfig.setSubMchId(request.getSubMchId());
		PayConfig payConfig = payConfigService.findByType(PayConstants.PAY_TYPE_1);
		if (payConfig == null) {
			// 取消未付款单前的查单最常撞到这里（店铺上还留着微信商户号，但 type=1 已删）。
			// mall-api 拿到 R.failed 不会中断取消：交叉查时按「没查到支付」处理，
			// 按 payment_type=1 精确路由时按「无法判定」处理、由逃生门兜底。
			return R.failed(WxPayConfiguration.NOT_CONFIGURED);
		}
		if(PayConstants.PAY_MODEL_2.equals(payConfig.getModel())){//普通模式
			request.setSubMchId(null);
			request.setSubAppId(null);
			wxPayConfig.setSubMchId(null);
			wxPayConfig.setSubAppId(null);
		}
		WxPayService wxPayService = WxPayConfiguration.getPayService(wxPayConfig);
		try {
			WxPayOrderQueryResult wxPayOrderQueryResult = ExtCallMetrics.record(EXT_DEP, "query",
					() -> wxPayService.queryOrder(request), null, WxPayController::classify);
			return R.ok(project(wxPayOrderQueryResult));
		} catch (WxPayException e) {
			// 单独打出机器可读的 errCode（如 ORDERNOTEXIST）：返回值里只有给人看的描述，
			// 而 mall 侧要靠它把「订单不存在」与「网关故障」区分开
			// （目前两者都归为「无法判定」，只能靠订单年龄逃生门兜底，见
			// mall-api OrderInfoServiceImpl 的 UNKNOWN_ESCAPE_HATCH_HOURS 注释）。
			// 返回值格式保持原样不动，避免影响任何按 msg 判断的调用方。
			log.error("微信查单失败 outTradeNo={}, errCode={}, errCodeDes={}, returnMsg={}",
					request.getOutTradeNo(), e.getErrCode(), e.getErrCodeDes(), e.getReturnMsg());
			return R.failed(e.getReturnMsg() + "" + e.getCustomErrorMsg() + "" + e.getErrCodeDes());
		}
	}

	/** [B6 契约治理] 查单请求投影 → SDK 请求对象。字段见 {@link WxOrderQueryReq}。 */
	static WxPayOrderQueryRequest toSdkRequest(WxOrderQueryReq req) {
		WxPayOrderQueryRequest request = new WxPayOrderQueryRequest();
		request.setAppid(req.getAppid());
		request.setSubMchId(req.getSubMchId());
		request.setTransactionId(req.getTransactionId());
		request.setOutTradeNo(req.getOutTradeNo());
		return request;
	}

	/** [B6 契约治理] 分账请求投影 → SDK 请求对象。字段见 {@link WxProfitSharingReq}。 */
	static ProfitSharingRequest toSdkRequest(WxProfitSharingReq req) {
		ProfitSharingRequest request = new ProfitSharingRequest();
		request.setAppid(req.getAppid());
		request.setSubMchId(req.getSubMchId());
		request.setTransactionId(req.getTransactionId());
		request.setOutOrderNo(req.getOutOrderNo());
		request.setReceivers(req.getReceivers());
		return request;
	}

	/** [B6 契约治理] 分账完结请求投影 → SDK 请求对象。字段见 {@link WxProfitSharingFinishReq}。 */
	static ProfitSharingUnfreezeRequest toSdkRequest(WxProfitSharingFinishReq req) {
		ProfitSharingUnfreezeRequest request = new ProfitSharingUnfreezeRequest();
		request.setAppid(req.getAppid());
		request.setSubMchId(req.getSubMchId());
		request.setTransactionId(req.getTransactionId());
		request.setOutOrderNo(req.getOutOrderNo());
		request.setDescription(req.getDescription());
		return request;
	}

	// ================================================================= 响应投影

	/**
	 * [B6 契约治理] SDK 回调结果 → 跨服务投影。
	 *
	 * <p>为什么要投影而不是把 SDK 对象原样传出去：SDK 类型跨在契约边界上意味着
	 * 升个版本契约就变形，而且 proto 里写不出来。取哪些字段的依据是
	 * 「服务端真的产出 ∩ 调用方真的读取」，逐点核过的结果写在 {@link WxOrderNotifyDTO} 里。
	 */
	static WxOrderNotifyDTO project(WxPayOrderNotifyResult src) {
		WxOrderNotifyDTO out = new WxOrderNotifyDTO();
		out.setOutTradeNo(src.getOutTradeNo());
		out.setTradeType(src.getTradeType());
		out.setTotalFee(src.getTotalFee());
		out.setTimeEnd(src.getTimeEnd());
		out.setTransactionId(src.getTransactionId());
		out.setAttach(src.getAttach());
		return out;
	}

	/** SDK 查单结果 → 跨服务投影。字段取舍见 {@link WxOrderQueryDTO}。 */
	static WxOrderQueryDTO project(WxPayOrderQueryResult src) {
		WxOrderQueryDTO out = new WxOrderQueryDTO();
		out.setTradeState(src.getTradeState());
		out.setTradeStateDesc(src.getTradeStateDesc());
		out.setTotalFee(src.getTotalFee());
		out.setTimeEnd(src.getTimeEnd());
		out.setTransactionId(src.getTransactionId());
		out.setTradeType(src.getTradeType());
		out.setOutTradeNo(src.getOutTradeNo());
		return out;
	}
}
