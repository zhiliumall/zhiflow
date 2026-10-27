package com.zhiflow.api.weixin.dto;

import lombok.Data;

/**
 * {@code WxAppApi#getById} 这条边上的微信应用配置投影。
 *
 * <p><b>字段集合 = 今天服务端真的返回的那些</b>：{@code WxAppController#getByIdInside} 在返回前
 * 已经手工把 {@code secret / token / aesKey} 置空（微信应用密钥、消息校验 token、消息加解密 AESKey ——
 * 泄漏范围是整个租户的微信账号控制权）。投影层面<b>干脆不声明这三个字段</b>：
 * 它们今天就是 {@code null}，而写在契约里只会让将来某个分支忘了置空时重新把它们带出仓。
 *
 * <p>后台 CRUD 审计字段（{@code createId / updateId}）与框架字段（{@code delFlag / createTime /
 * updateTime}）同样不进：全仓无跨进程消费者。
 *
 * <p>开源版 {@code wx_app} 只留网页授权 / 小程序登录要的 appid、名称、logo、备注；公众号运营列
 * （微信号、二维码、认证类型、第三方平台、会员卡、店铺等）已随功能删除。
 */
@Data
public class WxAppInsideDTO {

	/** 微信 AppID（wx_app 主键）。 */
	private String id;

	/** 应用类型（1：小程序，2：公众号）。 */
	private String appType;

	/** 微信号名称。 */
	private String name;

	/** logo。 */
	private String logo;

	/** 备注信息。 */
	private String remarks;

	/** 所属租户。 */
	private String tenantId;

}
