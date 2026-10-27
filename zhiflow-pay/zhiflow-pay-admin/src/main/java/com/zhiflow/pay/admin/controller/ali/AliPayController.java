/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.pay.admin.controller.ali;

import cn.hutool.core.util.StrUtil;
import com.alipay.easysdk.factory.MultipleFactory;
import com.alipay.easysdk.payment.app.models.AlipayTradeAppPayResponse;
import com.alipay.easysdk.payment.common.models.AlipayTradeQueryResponse;
import com.alipay.easysdk.payment.facetoface.models.AlipayTradePrecreateResponse;
import com.alipay.easysdk.payment.page.models.AlipayTradePagePayResponse;
import com.alipay.easysdk.payment.wap.models.AlipayTradeWapPayResponse;
import com.alipay.easysdk.util.generic.models.AlipayOpenApiGenericResponse;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.security.annotation.Inside;
import com.zhiflow.pay.admin.config.AliPayConfiguration;
import com.zhiflow.pay.admin.service.PayConfigService;
import com.zhiflow.pay.common.constant.PayConstants;
import com.zhiflow.pay.common.dto.AliBaseRequest;
import com.zhiflow.pay.common.dto.inside.AliOrderNotifyDTO;
import com.zhiflow.pay.common.dto.inside.AliOrderQueryDTO;
import com.zhiflow.pay.common.dto.inside.AliOrderQueryReq;
import com.zhiflow.pay.common.dto.inside.AliProfitSharingDTO;
import com.zhiflow.pay.common.dto.inside.AliProfitSharingReq;
import com.zhiflow.pay.common.dto.inside.AliRoyaltyParameter;
import com.zhiflow.pay.common.dto.inside.AliUnifiedOrderReq;
import com.zhiflow.pay.common.entity.PayConfig;
import tools.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付宝支付
 *
 * @date 2021-03-23 21:26:35
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/alipay")
@Tag(description = "alipay", name = "支付宝支付")
public class AliPayController {

	/**
	 * ⚠️ 本类每个接口在取 {@code AliPayConfiguration.getFactory()} 之前先查 type=2 配置，
	 * 没配置就回 {@code R.failed(AliPayConfiguration.NOT_CONFIGURED)}（2026-09-29）。
	 * 以前是 getFactory 抛受检异常、被 {@link #wrapChecked} 包成 IllegalStateException ⇒
	 * gRPC 下「未映射的异常 → INTERNAL」（mall-api 取消未付款单前查单，12 天 9 次）。
	 * 用「返回」而不是「抛」的理由同 {@code WxPayController} 同处。
	 */
	private final PayConfigService payConfigService;

	/**
	 * [B6] 与 MVC 同一个 ObjectMapper —— {@link #unifiedOrder} 要把统一下单结果序列化成
	 * <b>原始 JSON</b>，理由同 {@code WxPayController}。
	 */
	private final ObjectMapper objectMapper;

	/**
	 * 调用统一下单接口（支付宝）
	 *
	 * @param aliBaseRequest 统一下单请求参数
	 * @return
	 */
	@Operation(summary = "调用统一下单接口（支付宝）")
	@Inside
	@PostMapping("/unifiedOrder")
	public R<String> unifiedOrder(@RequestBody AliUnifiedOrderReq req) {
		try {
			return unifiedOrderInternal(req);
		}
		catch (RuntimeException e) {
			throw e;
		}
		catch (Exception e) {
			throw wrapChecked(e);
		}
	}

	/** 原方法体，只把受检异常留在这一层；外层那个公开方法负责包成 unchecked。 */
	private R<String> unifiedOrderInternal(AliUnifiedOrderReq req) throws Exception {
		AliBaseRequest aliBaseRequest = toBaseRequest(req);
		log.debug("调用统一下单接口（支付宝）");
		Map<String,Object> params = aliBaseRequest.getParams();
		String tradeType = params.get("tradeType").toString();
		PayConfig payConfig = payConfigService.findByType(PayConstants.PAY_TYPE_2);
		if (payConfig == null) {
			return R.failed(AliPayConfiguration.NOT_CONFIGURED);
		}
		MultipleFactory multipleFactory = AliPayConfiguration.getFactory();
		String appAuthToken = aliBaseRequest.getAppAuthToken();
		Map<String, Object> optionalArgs = new HashMap<>();
		if(PayConstants.PAY_MODEL_2.equals(payConfig.getModel())){//普通模式
			appAuthToken = null;
		}
		if("NATIVE".equals(tradeType)){//电脑网页
			AlipayTradePagePayResponse alipayTradePagePayResponse = multipleFactory.Page()
					.agent(appAuthToken)
					.asyncNotify(params.get("notifyUrl").toString())
					.batchOptional(optionalArgs)
					.pay(params.get("subject").toString(),params.get("outTradeNo").toString(),params.get("totalAmount").toString(),params.get("returnUrl").toString());
			return R.ok(objectMapper.writeValueAsString(alipayTradePagePayResponse));
		}else if("APP".equals(tradeType)){//APP
			AlipayTradeAppPayResponse alipayTradeAppPayResponse = multipleFactory.App()
					.agent(appAuthToken)
					.asyncNotify(params.get("notifyUrl").toString())
					.batchOptional(optionalArgs)
					.pay(params.get("subject").toString(),params.get("outTradeNo").toString(),params.get("totalAmount").toString());
			return R.ok(objectMapper.writeValueAsString(alipayTradeAppPayResponse));
		}else if("MWEB".equals(tradeType)){//手机网站支付
			AlipayTradeWapPayResponse alipayTradeWapPayResponse = multipleFactory.Wap()
					.agent(appAuthToken)
					.asyncNotify(params.get("notifyUrl").toString())
					.batchOptional(optionalArgs)
					.pay(params.get("subject").toString(),params.get("outTradeNo").toString(),params.get("totalAmount").toString(),params.get("quitUrl").toString(),params.get("returnUrl").toString());
			return R.ok(objectMapper.writeValueAsString(alipayTradeWapPayResponse));
		}else if("QRCODE".equals(tradeType)){//扫码
			AlipayTradePrecreateResponse alipayTradePrecreateResponse = multipleFactory.FaceToFace()
					.agent(appAuthToken)
					.asyncNotify(params.get("notifyUrl").toString())
					.batchOptional(optionalArgs)
					.preCreate(params.get("subject").toString(), params.get("outTradeNo").toString(), params.get("totalAmount").toString());
			if(!PayConstants.ALI_RES_CODE_SUCCESS.equals(alipayTradePrecreateResponse.getCode())){
				log.error(StrUtil.format("{}:{}:{}:{}",
						alipayTradePrecreateResponse.getCode(),alipayTradePrecreateResponse.getMsg(),alipayTradePrecreateResponse.getSubCode(),alipayTradePrecreateResponse.getSubMsg()));
				return R.failed(alipayTradePrecreateResponse.getMsg()+":"+alipayTradePrecreateResponse.getSubMsg());
			}
			return R.ok(objectMapper.writeValueAsString(alipayTradePrecreateResponse));
		}else{
			return R.failed("该订单无支付类型");
		}
	}

	/**
	 * [B6 契约治理] 下单请求投影 → 原来的弱类型 {@link AliBaseRequest}。
	 *
	 * <p><b>刻意逐字装回同名 key、包括可能为 null 的那几个</b>：上面的分支里
	 * {@code params.get("returnUrl").toString()} 在 key 缺失时会 NPE，
	 * 而今天调用方 put 的就是「可能为 null 的值」——两者行为相同（{@code get} 都返回 null）。
	 * 换成「非空才 put」或「补默认值」都会改变今天的失败面，不属于恒等迁移。
	 */
	static AliBaseRequest toBaseRequest(AliUnifiedOrderReq req) {
		Map<String, Object> params = new HashMap<>();
		params.put("tradeType", req.getTradeType());
		params.put("outTradeNo", req.getOutTradeNo());
		// 金额在契约层是 BigDecimal（元），这里还原成支付宝要的十进制字符串。
		// toPlainString() 不出科学计数法，与调用方今天写的 paymentPrice.toString() 逐字相同。
		params.put("totalAmount", req.getTotalAmount() == null ? null : req.getTotalAmount().toPlainString());
		params.put("notifyUrl", req.getNotifyUrl());
		params.put("returnUrl", req.getReturnUrl());
		params.put("quitUrl", req.getQuitUrl());
		params.put("subject", req.getSubject());
		AliBaseRequest out = new AliBaseRequest();
		out.setAppAuthToken(req.getAppAuthToken());
		out.setParams(params);
		return out;
	}

	/**
	 * 验签并解析支付回调（支付宝，方案 S-2）。
	 *
	 * <p>easysdk {@code verifyNotify} 验签，再核对 {@code app_id} 与 {@code seller_id}（收款配置的 mchId 填商户 PID，
	 * 2088 开头）。任何一项不过都回 {@code R.failed}，调用方据此<b>不回</b> {@code success}，让支付宝按策略重推。
	 * 验签不留开关。
	 */
	@Operation(summary = "验签并解析支付回调（支付宝）")
	@Inside
	@PostMapping("/notifyOrder")
	public R<AliOrderNotifyDTO> notifyOrder(@RequestBody Map<String, String> params) {
		PayConfig payConfig = payConfigService.findByType(PayConstants.PAY_TYPE_2);
		if (payConfig == null) {
			return R.failed(AliPayConfiguration.NOT_CONFIGURED);
		}
		boolean signOk;
		try {
			// easysdk 验签时会从入参里 remove 掉 sign / sign_type，必须传可变副本：
			// 回调入口传进来的是只读 Map（单进程直调，不再经序列化变成 HashMap），直接传会抛 UnsupportedOperationException
			signOk = Boolean.TRUE.equals(AliPayConfiguration.getFactory().Common().verifyNotify(new HashMap<>(params)));
		}
		catch (Exception e) {
			log.error("支付宝回调验签异常 outTradeNo={}", params.get("out_trade_no"), e);
			return R.failed("验签异常");
		}
		if (!signOk) {
			log.error("支付宝回调验签失败 outTradeNo={}", params.get("out_trade_no"));
			return R.failed("验签失败");
		}
		if (!StrUtil.equals(params.get("app_id"), payConfig.getAppId())) {
			log.error("支付宝回调 app_id 与收款配置不符 outTradeNo={} appId={}", params.get("out_trade_no"), params.get("app_id"));
			return R.failed("app_id 不符");
		}
		if (StrUtil.isBlank(payConfig.getMchId()) || !payConfig.getMchId().equals(params.get("seller_id"))) {
			// 没配 PID 也拒：不核卖家等于接受任意商户的成功通知
			log.error("支付宝回调 seller_id 与收款配置不符（或收款配置未填商户 PID）outTradeNo={} sellerId={}",
					params.get("out_trade_no"), params.get("seller_id"));
			return R.failed("seller_id 不符");
		}
		AliOrderNotifyDTO out = new AliOrderNotifyDTO();
		out.setTradeStatus(params.get("trade_status"));
		out.setOutTradeNo(params.get("out_trade_no"));
		out.setTradeNo(params.get("trade_no"));
		out.setTotalAmount(decimal(params.get("total_amount")));
		out.setGmtPayment(params.get("gmt_payment"));
		out.setNotifyId(params.get("notify_id"));
		return R.ok(out);
	}

	/**
	 * 查询订单（支付宝）
	 * @param aliBaseRequest
	 * @return
	 */
	@Operation(summary = "查询订单（支付宝）")
	@Inside
	@PostMapping("/queryOrder")
	public R<AliOrderQueryDTO> queryOrder(@RequestBody AliOrderQueryReq req) {
		try {
			return queryOrderInternal(req);
		}
		catch (RuntimeException e) {
			throw e;
		}
		catch (Exception e) {
			throw wrapChecked(e);
		}
	}

	/** 原方法体，只把受检异常留在这一层；外层那个公开方法负责包成 unchecked。 */
	private R<AliOrderQueryDTO> queryOrderInternal(AliOrderQueryReq req) throws Exception {
		AliBaseRequest aliBaseRequest = toBaseRequest(req);
		log.debug("查询订单（支付宝）");
		Map<String,Object> params = aliBaseRequest.getParams();
		PayConfig payConfig = payConfigService.findByType(PayConstants.PAY_TYPE_2);
		if (payConfig == null) {
			return R.failed(AliPayConfiguration.NOT_CONFIGURED);
		}
		MultipleFactory multipleFactory = AliPayConfiguration.getFactory();
		String appAuthToken = aliBaseRequest.getAppAuthToken();
		if(PayConstants.PAY_MODEL_2.equals(payConfig.getModel())){//普通模式
			appAuthToken = null;
		}
		AlipayTradeQueryResponse alipayTradeQueryResponse = multipleFactory.Common().agent(appAuthToken).query(params.get("outTradeNo").toString());
		if(PayConstants.ALI_RES_CODE_SUCCESS.equals(alipayTradeQueryResponse.getCode())){
			return R.ok(project(alipayTradeQueryResponse));
		}else{
			return R.failed(project(alipayTradeQueryResponse),alipayTradeQueryResponse.getMsg()+"："+alipayTradeQueryResponse.getSubMsg());
		}
	}

	/** [B6 契约治理] 查单请求投影 → 弱类型 {@link AliBaseRequest}。只有 outTradeNo 一个 key，与今天一致。 */
	static AliBaseRequest toBaseRequest(AliOrderQueryReq req) {
		Map<String, Object> params = new HashMap<>();
		params.put("outTradeNo", req.getOutTradeNo());
		AliBaseRequest out = new AliBaseRequest();
		out.setAppAuthToken(req.getAppAuthToken());
		out.setParams(params);
		return out;
	}

	/**
	 * 分账接口
	 * @param aliBaseRequest
	 * @return
	 */
	@SneakyThrows
	private AlipayOpenApiGenericResponse profitSharing(AliBaseRequest aliBaseRequest){
		log.debug("分账接口（支付宝）");
		MultipleFactory multipleFactory = AliPayConfiguration.getFactory();
		PayConfig payConfig = payConfigService.findByType(PayConstants.PAY_TYPE_2);
		String appAuthToken = aliBaseRequest.getAppAuthToken();
		if(PayConstants.PAY_MODEL_2.equals(payConfig.getModel())){//普通模式
			appAuthToken = null;
		}
		Map<String, String> textParams = new HashMap<>();
		Map<String, ?> bizParams = aliBaseRequest.getParams();
		return multipleFactory.Generic().agent(appAuthToken).execute("alipay.trade.order.settle", textParams, bizParams);
	}

	/**
	 * 分账关系绑定
	 * @param aliBaseRequest
	 * @return
	 */
	@SneakyThrows
	private AlipayOpenApiGenericResponse profitSharingRelationBind(AliBaseRequest aliBaseRequest){
		log.debug("分账关系绑定）");
		MultipleFactory multipleFactory = AliPayConfiguration.getFactory();
		PayConfig payConfig = payConfigService.findByType(PayConstants.PAY_TYPE_2);
		String appAuthToken = aliBaseRequest.getAppAuthToken();
		if(PayConstants.PAY_MODEL_2.equals(payConfig.getModel())){//普通模式
			appAuthToken = null;
		}
		Map<String, String> textParams = new HashMap<>();
		Map<String, ?> bizParams = aliBaseRequest.getParams();
		AlipayOpenApiGenericResponse alipayOpenApiGenericResponse = multipleFactory.Generic().agent(appAuthToken).execute("alipay.trade.royalty.relation.bind", textParams, bizParams);
		return alipayOpenApiGenericResponse;
	}

	/**
	 * [B6 契约治理] 分账请求投影 → 弱类型 {@link AliBaseRequest}。
	 *
	 * <p>key 名用支付宝 {@code alipay.trade.order.settle} 要求的 snake_case，与今天那段
	 * （被注释掉的）调用方草稿逐字一致；{@code royaltyFinish} 落进 {@code extend_params}。
	 * ⚠️ 零活调用点，见 {@link AliProfitSharingReq}。
	 */
	static AliBaseRequest toBaseRequest(AliProfitSharingReq req) {
		Map<String, Object> params = new HashMap<>();
		params.put("out_request_no", req.getOutRequestNo());
		params.put("trade_no", req.getTradeNo());
		List<Map<String, Object>> royaltyParameters = new ArrayList<>();
		if (req.getRoyaltyParameters() != null) {
			for (AliRoyaltyParameter item : req.getRoyaltyParameters()) {
				Map<String, Object> royaltyParameter = new HashMap<>();
				royaltyParameter.put("trans_in_type", item.getTransInType());
				royaltyParameter.put("trans_in", item.getTransIn());
				royaltyParameter.put("trans_in_name", item.getTransInName());
				royaltyParameter.put("amount", item.getAmount());
				royaltyParameter.put("royalty_scene", item.getRoyaltyScene());
				royaltyParameters.add(royaltyParameter);
			}
		}
		params.put("royalty_parameters", royaltyParameters);
		if (req.getRoyaltyFinish() != null) {
			Map<String, Object> extendParams = new HashMap<>();
			extendParams.put("royalty_finish", req.getRoyaltyFinish());
			params.put("extend_params", extendParams);
		}
		AliBaseRequest out = new AliBaseRequest();
		out.setAppAuthToken(req.getAppAuthToken());
		out.setParams(params);
		return out;
	}

	// ================================================================= 响应投影

	/**
	 * [B6 契约治理] SDK 查单响应 → 跨服务投影。字段取舍与金额口径见 {@link AliOrderQueryDTO}。
	 *
	 * <p>失败分支也投影（{@code R.failed(data, msg)}）：今天服务端在失败时<b>也</b>把响应放进
	 * {@code R.data}，裁掉它是行为变化。虽然全仓没有调用方在 {@code !isOk()} 时读 data
	 * （2026-09-14 逐点核过 4 个调用点，全是 {@code r.isOk() && r.getData() != null} 才往下走），
	 * 但保住它零成本。
	 */
	static AliOrderQueryDTO project(AlipayTradeQueryResponse src) {
		AliOrderQueryDTO out = new AliOrderQueryDTO();
		out.setCode(src.code);
		out.setMsg(src.msg);
		out.setSubCode(src.subCode);
		out.setSubMsg(src.subMsg);
		out.setTradeStatus(src.tradeStatus);
		out.setTotalAmount(decimal(src.totalAmount));
		out.setSendPayDate(src.sendPayDate);
		out.setTradeNo(src.tradeNo);
		out.setOutTradeNo(src.outTradeNo);
		return out;
	}

	/** SDK 通用响应壳 → 分账结果投影。为什么保留四个字段而不是治成 R<Void>，见 {@link AliProfitSharingDTO}。 */
	static AliProfitSharingDTO project(AlipayOpenApiGenericResponse src) {
		AliProfitSharingDTO out = new AliProfitSharingDTO();
		out.setCode(src.code);
		out.setMsg(src.msg);
		out.setSubCode(src.subCode);
		out.setSubMsg(src.subMsg);
		return out;
	}

	/**
	 * 支付宝的金额字段本来就是<b>元制十进制字符串</b>，转 {@code BigDecimal} 是恒等转换，
	 * 不是单位换算（对比微信侧的 {@code Integer} 分，那边刻意<b>不</b>换算）。
	 * 空串与 null 都当「无值」——{@code new BigDecimal("")} 会抛 NumberFormatException。
	 */
	private static java.math.BigDecimal decimal(String value) {
		return StrUtil.isBlank(value) ? null : new java.math.BigDecimal(value);
	}

	/**
	 * [B6] 受检异常在<b>这里</b>包成 unchecked，而不是在契约实现里。
	 *
	 * <p>原先这四个 handler 声明 {@code throws Exception}，由 {@code ZhiflowPayLocalApi}
	 * 逐个 try/catch 包成 {@code IllegalStateException}。B6 把 gRPC 服务端也接到同一批 controller 上，
	 * 而生成物走的是 {@code Supplier}（不能抛受检异常）—— 两条传输各写一份包装既是重复，
	 * 也迟早会分叉。收口到 controller 自己：<b>两条传输与单体本地实现拿到的异常形状完全一样</b>。
	 *
	 * <p>等价性（沿用的论证）：HTTP 路径下该受检异常被 {@code GlobalBizExceptionHandler}
	 * 的 {@code Exception.class} 兜底变成 500；{@code IllegalStateException} 没有专用 handler，
	 * 同样落进那条兜底 ⇒ 对调用方逐字等价。
	 * ⚠️ 不能改用 {@code IllegalArgumentException} / {@code BizException} —— 它们各有专用 handler，
	 * 会改掉响应体与状态码语义。{@code RuntimeException} 原样重抛，保留原始类型的诊断信息。
	 */
	private static IllegalStateException wrapChecked(Exception e) {
		return new IllegalStateException("支付接口调用失败: " + e.getMessage(), e);
	}
}
