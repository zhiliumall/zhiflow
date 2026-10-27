package com.zhiflow.api.weixin.dto;

import lombok.Data;

/**
 * {@code WxUserApi#wxUserMp} 这条边上的公众号用户投影，对应 SDK 类型
 * {@code me.chanjar.weixin.mp.bean.result.WxMpUser}（{@code weixin-java-mp}）。
 *
 * <p><b>为什么 1:1 全量搬</b>：这是一条「服务端产出 → 内部契约 → 外部 REST 响应」的全字段透传链 ——
 * {@code UserInfoServiceImpl#isGzh} 拿到整对象原样返回，mall-api {@code POST /wxuser/isGzh}
 * 再整对象吐给 H5。Java 侧没有任何字段级裁剪，所以砍字段等于改外部响应。
 * H5 实测只读 {@code subscribe}（{@code pd-subscribe-guide.vue}），其余字段属「今天就在返回」。
 *
 * <p>不含敏感字段：{@code openId / unionId} 是自家前端本来就要拿的标识符。
 * 换成投影 DTO 的收益是把 SDK 类型挡在契约外 —— {@code weixin-java-mp} 升级改字段名时，
 * 今天会静默丢字段，改成投影之后是编译错误。
 */
@Data
public class WxMpUserInsideDTO {

	/** 是否关注公众号。 */
	private Boolean subscribe;

	/** 用户 openId。 */
	private String openId;

	/** 昵称。 */
	private String nickname;

	/** 用户语言。 */
	private String language;

	/** 头像 URL。 */
	private String headImgUrl;

	/** 关注时间（微信下发的 epoch 秒，SDK 侧就是 Long）。 */
	private Long subscribeTime;

	/** 开放平台 unionId。 */
	private String unionId;

	/** 公众号侧备注。 */
	private String remark;

	/** 用户分组 ID。 */
	private Integer groupId;

	/** 标签 ID 列表。 */
	private java.util.List<Long> tagIds;

	/** 用户特权信息。 */
	private java.util.List<String> privileges;

	/** 关注渠道来源。 */
	private String subscribeScene;

	/** 二维码扫码场景（数字）。 */
	private String qrScene;

	/** 二维码扫码场景（字符串）。 */
	private String qrSceneStr;

}
