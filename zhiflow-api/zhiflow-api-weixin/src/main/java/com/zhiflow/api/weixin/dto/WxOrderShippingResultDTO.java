package com.zhiflow.api.weixin.dto;

import lombok.Data;

/**
 * {@code WxMaOrderShippingApi#upload} 的<b>响应</b>投影，对应 SDK 类型
 * {@code cn.binarywang.wx.miniapp.bean.shop.response.WxMaOrderShippingInfoBaseResponse}。
 *
 * <p>两个调用点（{@code ConsumerListener#wxMaOrderShippingUpload}、mall-admin
 * {@code OrderInfoServiceImpl#wxMaOrderShippingUpload}）都<b>不检查返回值</b>。
 * 仍然不折成 {@code R<Void>}：这是微信官方错误码，两个字段的成本近似为零，
 * 而排障时「微信到底回了什么」是唯一能看的东西。
 *
 * <p>⚠️ 服务端在「小程序未完成交易结算管理确认」时走的是 {@code return R.ok();} ——
 * 也就是 {@code data} 为 {@code null}。投影必须能表达「没有结果」，所以是信封里的可选消息，
 * 不是把两个字段平铺到响应上。
 */
@Data
public class WxOrderShippingResultDTO {

	/** 微信返回的错误码（0 表示成功）。 */
	private Integer errCode;

	/** 微信返回的错误文案。 */
	private String errMsg;

}
