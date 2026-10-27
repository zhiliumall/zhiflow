package com.zhiflow.pay.admin.local;

import com.zhiflow.api.pay.ZhiflowPayApi;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.security.internal.InternalCallContext;
import com.zhiflow.pay.admin.controller.ali.AliPayController;
import com.zhiflow.pay.admin.controller.wx.WxPayController;
import com.zhiflow.pay.common.dto.inside.AliOrderNotifyDTO;
import com.zhiflow.pay.common.dto.inside.AliOrderQueryDTO;
import com.zhiflow.pay.common.dto.inside.AliOrderQueryReq;
import com.zhiflow.pay.common.dto.inside.AliUnifiedOrderReq;
import com.zhiflow.pay.common.dto.inside.WxOrderNotifyDTO;
import com.zhiflow.pay.common.dto.inside.WxOrderQueryDTO;
import com.zhiflow.pay.common.dto.inside.WxOrderQueryReq;
import com.zhiflow.pay.common.dto.inside.WxUnifiedOrderReq;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ZhiflowPayApi} 的进程内实现（单体内直调，跳过 HTTP，**资金主路径**）。
 *
 * <p>映射依据：契约接口级前缀 {@code /pay} + 方法级路径，逐条对到 pay-admin 两个 controller
 * 的「类级 @RequestMapping + 方法级 @XxxMapping」组合。
 *
 * <p><b>受检异常</b>：{@code AliPayController} 的 unifiedOrder / queryOrder 与
 * {@code WxPayController#notifyOrder} 原先声明受检异常，由本类逐个包成
 * {@code IllegalStateException("支付接口调用失败: …", e)}。后来包装收口进了 controller 自己
 * （{@code AliPayController#wrapChecked}），本类改为直调。
 * 等价性论证：HTTP 路径下该异常被被调方 {@code GlobalBizExceptionHandler} 的
 * {@code Exception.class} 兜底变成 500，调用方拿到 RestClient 的 unchecked 异常；
 * 全仓无一处 catch 具体 RestClient 异常类型 ⇒ 「异常类型」对调用方不可观测，可观测的只有「抛不抛」。
 * {@code IllegalStateException} 在 {@code GlobalBizExceptionHandler} 没有专用 handler、
 * 同样落进 {@code Exception.class} 兜底 ⇒ 与今天逐字等价。
 * ⚠️ 不能用 {@code IllegalArgumentException} / {@code BizException} 做包装 ——
 * 它们各有专用 handler，会改变响应体与状态码语义。
 * {@code throws Exception} 的几处只包**受检**部分，{@code RuntimeException} 原样重抛，
 * 不做二次包装（保留原始类型的诊断信息，且与 HTTP 路径同样「抛 unchecked」）。
 *
 * <p>四条硬约束由门禁 {@code ContractLocalImplGateTest} 守：{@code @Primary}、
 * {@code REQUIRES_NEW}、{@code InternalCallContext}、以及必须待在目标服务
 * （ZHIFLOW_PAY_ADMIN → {@code com.zhiflow.pay.admin}）的模块里。
 */
@Primary
@Component
public class ZhiflowPayLocalApi implements ZhiflowPayApi {

	private final WxPayController wxPayController;

	private final AliPayController aliPayController;

	/**
	 * ⚠️ 显式构造器 + {@code @Lazy}：controller 会牵出 service → mapper →
	 * {@code sqlSessionFactory} 一整条链，急切注入会把它提前到容器早期、引爆本仓既有的
	 * 循环引用（2026-08-16 upms 实测起不来）。不用 {@code @RequiredArgsConstructor}：
	 * 本仓没配 Lombok 的 {@code copyableAnnotations}，字段上的 {@code @Lazy}
	 * 不会复制到生成的构造器参数上。理由同其余 51 个实现。
	 */
	public ZhiflowPayLocalApi(@Lazy WxPayController wxPayController,
			@Lazy AliPayController aliPayController) {
		this.wxPayController = wxPayController;
		this.aliPayController = aliPayController;
	}

	// ------------------------------------------------------------ wxpay（国内微信，服务商/普通模式）

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<String> unifiedOrderWx(WxUnifiedOrderReq request, String from) {
		return InternalCallContext.call(() -> this.wxPayController.unifiedOrder(request));
	}

	@Override
	@SuppressWarnings("unchecked")
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<WxOrderNotifyDTO> notifyOrderWx(String xmlData, String from) {
		return InternalCallContext.call(() -> this.wxPayController.notifyOrder(xmlData));
	}

	@Override
	@SuppressWarnings("unchecked")
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<WxOrderQueryDTO> queryOrderWx(WxOrderQueryReq request, String from) {
		return InternalCallContext.call(() -> this.wxPayController.queryOrder(request));
	}

	// ------------------------------------------------------------ alipay（国内支付宝；受检异常已收口进 controller）

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<String> unifiedOrderAli(AliUnifiedOrderReq request, String from) {
		return InternalCallContext.call(() -> this.aliPayController.unifiedOrder(request));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<AliOrderNotifyDTO> notifyOrderAli(java.util.Map<String, String> params, String from) {
		return InternalCallContext.call(() -> this.aliPayController.notifyOrder(params));
	}

	@Override
	@SuppressWarnings("unchecked")
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<AliOrderQueryDTO> queryOrderAli(AliOrderQueryReq request, String from) {
		return InternalCallContext.call(() -> this.aliPayController.queryOrder(request));
	}

	/**
	 * 复刻 HTTP interface 客户端对 {@code required=true} 的
	 * {@code @RequestParam} 的行为：**参数为 null 时在发起前就抛
	 * {@code IllegalArgumentException}，请求根本不会发出**（spring-web 6.2 实证，
	 * 消息格式同 {@code RequestParamArgumentResolver}）。
	 *
	 * <p>不加这道守卫的话，本地直调会把 null 透传进 controller，让「HTTP 形态下从来
	 * 不会发生的调用」真的发生——必填参数一旦悄悄变成 null，controller 内部可能走到
	 * 完全不同的分支，这类差异落在资金路径上尤其不可接受。
	 */
	private static String requireParam(String value, String name) {
		if (value == null) {
			throw new IllegalArgumentException("Missing request parameter value '" + name + "'");
		}
		return value;
	}
}
