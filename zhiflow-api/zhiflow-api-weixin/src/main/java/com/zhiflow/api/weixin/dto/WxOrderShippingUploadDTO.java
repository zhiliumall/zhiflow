package com.zhiflow.api.weixin.dto;

import java.util.List;

import lombok.Data;

/**
 * {@code WxMaOrderShippingApi#upload} 的<b>请求</b>投影，对应 SDK 类型
 * {@code cn.binarywang.wx.miniapp.bean.shop.request.shipping.WxMaOrderShippingInfoUploadRequest}。
 *
 * <p>服务端把整个对象原样转发给微信 SDK（不再挑字段），所以这里按 SDK 类型 1:1 全量搬 ——
 * 包含本仓两个调用点当前没填的字段（{@code mchId / outTradeNo / trackingNo / expressCompany /
 * contact / isAllDelivered}）。理由见 {@link WxOrderShippingContactDTO}：请求方向砍字段 = 砍能力。
 *
 * <p>{@code logisticsType} / {@code deliveryMode} 在 SDK 上是<b>原生 {@code int}</b>，保持原生类型。
 */
@Data
public class WxOrderShippingUploadDTO {

	/** 订单标识。 */
	private WxOrderShippingKeyDTO orderKey;

	/** 物流模式（1：实体物流；2：同城配送；3：虚拟商品；4：用户自提。SDK 侧是原生 int）。 */
	private int logisticsType;

	/** 发货模式（1：统一发货；2：分拆发货。SDK 侧是原生 int）。 */
	private int deliveryMode;

	/** 分拆发货时是否已全部发完。 */
	private Boolean isAllDelivered;

	/** 发货列表。 */
	private List<WxOrderShippingItemDTO> shippingList;

	/** 上传时间（微信要求的 RFC 3339 字符串，调用方自己格式化，服务端不解析）。 */
	private String uploadTime;

	/** 支付者。 */
	private WxOrderShippingPayerDTO payer;

}
