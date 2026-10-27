package com.zhiflow.api.pay;

import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.pay.common.dto.inside.AliOrderNotifyDTO;
import com.zhiflow.pay.common.dto.inside.AliOrderQueryDTO;
import com.zhiflow.pay.common.dto.inside.AliOrderQueryReq;
import com.zhiflow.pay.common.dto.inside.AliUnifiedOrderReq;
import com.zhiflow.pay.common.dto.inside.WxOrderNotifyDTO;
import com.zhiflow.pay.common.dto.inside.WxOrderQueryDTO;
import com.zhiflow.pay.common.dto.inside.WxOrderQueryReq;
import com.zhiflow.pay.common.dto.inside.WxUnifiedOrderReq;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.Map;

/**
 * 盖雅支付服务 API 接口定义
 *
 */
@HttpExchange(url = "/pay", accept = "application/json", contentType = "application/json")
// [B6] 11 个 @RequestBody 形参统一叫 req 而不是 request：gRPC 客户端生成物里
// 「请求消息」这个局部变量就叫 request，形参同名会自引用、编译不过。
// @RequestBody 的绑定不看形参名，改名零行为影响。
public interface ZhiflowPayApi {

	/**
	 * 调用统一下单接口,并组装生成支付所需参数对象(微信支付)。
	 *
	 * <p>[B6 契约治理] 返回从<b>裸 {@code R}</b> 治成 {@code R<String>}，内容是支付参数的
	 * <b>原始 JSON</b>。微信统一下单按 {@code tradeType} 在运行期返回四种互不相同的 SDK 类型
	 * （{@code Map} / MpOrderResult / NativeOrderResult / AppOrderResult / MwebOrderResult），
	 * 连 SDK 自己的 {@code createOrder} 都是裸泛型——把某一种钉进契约是错的。
	 * 全部 5 个调用方从来只是把它<b>原样转发给前端</b>，Java 侧一个字段都没解包过，
	 * 所以按 {@code im_inside.proto} 的 {@code raw_json} 先例传原始 JSON：
	 * 服务端用 MVC 同一个 ObjectMapper 序列化，调用方解回 Map 再吐给前端，前端看到的字节不变。
	 *
	 * <p>请求体从 SDK 类型治成 {@link WxUnifiedOrderReq}，理由见该类注释。
	 */
	@PostExchange("/wxpay/unifiedOrder")
	R<String> unifiedOrderWx(@RequestBody WxUnifiedOrderReq req,
					 @RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 处理支付回调数据(微信支付)
	 */
	@PostExchange("/wxpay/notifyOrder")
	R<WxOrderNotifyDTO> notifyOrderWx(@RequestBody String xmlData,
											@RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 查询订单(微信支付)
	 */
	@PostExchange("/wxpay/queryOrder")
	R<WxOrderQueryDTO> queryOrderWx(@RequestBody WxOrderQueryReq req,
										  @RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 调用统一下单接口(支付宝)。
	 *
	 * <p>[B6 契约治理] 返回从<b>裸 {@code R}</b> 治成 {@code R<String>}（原始 JSON），
	 * 成因与 {@link #unifiedOrderWx} 完全一样：按 {@code tradeType} 运行期返回
	 * Page/App/Wap/Precreate 四种 SDK 类型，5 个调用方一律原样转发给前端、零解包。
	 * 请求体从 {@code AliBaseRequest}（{@code Map<String,Object>}）治成 {@link AliUnifiedOrderReq}。
	 */
	@PostExchange("/alipay/unifiedOrder")
	R<String> unifiedOrderAli(@RequestBody AliUnifiedOrderReq req,
					  @RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 验签并解析支付回调（支付宝）。验签不通过、app_id / seller_id 与收款配置不符一律 {@code R.failed}。
	 *
	 * @param params 回调表单参数原样传入（含 sign / sign_type）
	 */
	@PostExchange("/alipay/notifyOrder")
	R<AliOrderNotifyDTO> notifyOrderAli(@RequestBody Map<String, String> params,
										@RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 查询订单(支付宝)
	 */
	@PostExchange("/alipay/queryOrder")
	R<AliOrderQueryDTO> queryOrderAli(@RequestBody AliOrderQueryReq req,
											  @RequestHeader(SecurityConstants.FROM) String from);

 }
