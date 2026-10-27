package com.zhiflow.api.weixin.dto;

import com.zhiflow.common.core.sensitive.Sensitive;
import com.zhiflow.common.core.sensitive.SensitiveTypeEnum;
import lombok.Data;

/**
 * {@code WxUserApi} 这条边上的微信用户投影 —— 服务端真的产出 ∩ 调用方真的读取。
 *
 * <p><b>为什么不直接用 {@code WxUser} 实体</b>：它是 {@code @TableName("wx_user")} 的持久化实体，
 * 契约层不出现实体，服务端改一个列名就从静默丢字段变成编译错误。
 *
 * <p>开源版 {@code wx_user} 只留 openid 映射（appId / appType / openId / unionId / mallUserId / 昵称头像性别 /
 * phone / sessionKey）；关注、分组、标签、地理位置、备注等公众号运营列已随功能删除。
 *
 * <p><b>{@code phone} 上的 {@code @Sensitive} 是刻意抄过来的，不是复制粘贴漏网。</b>
 * {@code @Sensitive} 就是 {@code @JsonSerialize}，脱敏发生在 Jackson 那一刻、且在<b>发送侧</b>：
 * 响应在服务端序列化、请求体在调用方序列化。今天 REST 下两个方向流通的都是 {@code 138****1234}。
 * 投影上不带这个注解 = 明文第一次跨进程流通 = 迁移引入的安全倒退。
 * （附带说明一个<b>既有</b>缺陷：写方向也被脱敏，意味着 {@code wx_user.phone} 列今天存的就是星号串。
 * 这不是本次迁移造成的，也刻意不在本次修 —— 见 REPORT.md「留给主会话」。）
 *
 * <p><b>{@code sessionKey}（微信会话密钥）没有脱敏注解，今天 REST 就是明文流通</b>，
 * 而且功能上必须双向流通（{@code code2WxUserMa} 响应 → mall-api 存 Redis →
 * 回传给 {@code updateByMaInside} / {@code getPhoneNumber} 解密）。删掉会打断登录链路，
 * 所以照旧保留；「它该不该留在同一个受信域外」是架构级问题，不在本任务范围。
 */
@Data
public class WxUserInsideDTO {

	/** wx_user 主键。 */
	private String id;

	/** 公众号配置 ID / 小程序 AppID。 */
	private String appId;

	/** 应用类型（1：小程序，2：公众号）。 */
	private String appType;

	/** 微信用户标识。 */
	private String openId;

	/** 开放平台 unionId（跨端账号打通靠它）。 */
	private String unionId;

	/** 绑定的商城用户 ID。 */
	private String mallUserId;

	/** 昵称。 */
	private String nickName;

	/** 头像。 */
	private String headimgUrl;

	/**
	 * 手机号。注解与实体逐字一致，保证 REST 路径上的取值不变；
	 * gRPC 路径由 codec 的 {@code FieldRule.SensitiveField(MASK_AS_REST)} 套同一个脱敏函数。
	 */
	@Sensitive(type = SensitiveTypeEnum.MOBILE_PHONE)
	private String phone;

	/** 小程序会话密钥。敏感但功能必需，见类注释。 */
	private String sessionKey;

	/** 所属租户（{@code TenantEntity} 继承字段，写方向是空操作）。 */
	private String tenantId;

	/** 性别。见类注释「被注释掉的消费点」。 */
	private String sex;

}
