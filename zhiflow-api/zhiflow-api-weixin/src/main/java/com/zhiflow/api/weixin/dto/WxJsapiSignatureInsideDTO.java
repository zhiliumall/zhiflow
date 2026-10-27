package com.zhiflow.api.weixin.dto;

import lombok.Data;

/**
 * {@code WxJsSdkApi#createJsapiSignature} 这条边上的 JS-SDK 签名投影，对应 SDK 类型
 * {@code me.chanjar.weixin.common.bean.WxJsapiSignature}。
 *
 * <p>5 个字段全部跨边界：mall-api {@code POST /wxjssdk/config} 整对象透传给 H5，
 * {@code utils/jweixin.js} 逐个读 {@code appId / timestamp / nonceStr / signature}，
 * {@code components/share-friends/index.vue} 另外读 {@code url}（2026-09-14 在 H5 前端实测 grep）。
 *
 * <p>{@code timestamp} 在 SDK 上是<b>原生 {@code long}</b>（不可能为 null），
 * 这里保持原生类型，proto 侧对应裸 {@code int64}。
 */
@Data
public class WxJsapiSignatureInsideDTO {

	/** 公众号 AppID。 */
	private String appId;

	/** 随机串。 */
	private String nonceStr;

	/** 签名时间戳（SDK 侧是原生 long）。 */
	private long timestamp;

	/** 参与签名的完整 URL。 */
	private String url;

	/** 签名。 */
	private String signature;

}
