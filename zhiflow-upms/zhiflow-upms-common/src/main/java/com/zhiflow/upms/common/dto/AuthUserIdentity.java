package com.zhiflow.upms.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 鉴权链路上的用户身份 —— {@code UserInfo.sysUser} 那一层的投影，取代把 {@code SysUser} 实体当契约。
 *
 * <h2>「一个都不能少」</h2>
 * <p>唯一的消费方是 {@code zhiflow-common-security} 的 {@code BaseUserDetailsServiceImpl#getUserDetails}，
 * 它是<b>全站四条登录链路</b>（密码 / 短信验证码 / 第三方 openId / 微信 code）共用的那一个方法，
 * 把这里的字段一对一塞进 Spring Security 的 {@code BaseUser} 构造器。
 * 2026-09-14 逐参数核过，{@code BaseUser} 的 11 个位置参数里有 7 个直接来自这里
 * （其余 4 个是 {@code enabled}、常量 true，以及由 {@link #passwordChangeRequired} 算出的 credentialsNonExpired）。
 * 少任何一个 = 登录功能性故障。
 *
 * <p>刻意不带：{@code email} / {@code avatar} / {@code nickName} / {@code switchTenantId} /
 * {@code salt}（{@code @JsonIgnore}，今天 REST 下本来就不出现在 JSON 里）/
 * {@code phone} / {@code TenantEntity} 的 {@code delFlag} / {@code createTime} / {@code updateTime}
 * —— 鉴权链路一个都不读。
 */
@Data
public class AuthUserIdentity implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private String id;

	private String organId;

	/** 账号类型：'-1' 系统管理员 '1' 商城管理员。 */
	private String type;

	private String tenantId;

	private String username;

	/**
	 * 裸 BCrypt 哈希，<b>不含</b> {@code {bcrypt}} 前缀 —— 前缀由
	 * {@code BaseUserDetailsServiceImpl} 自己拼。与今天 REST 逐字节一致
	 * （字段上没有 {@code @Sensitive}，Jackson 不脱敏）。
	 */
	private String password;

	/**
	 * 平台系统管理员绑定的租户列表。
	 *
	 * <p>⚠️ {@code SysUser.tenantIds} 是 {@code @TableField(exist=false)} 的瞬态字段，
	 * 全仓唯一一处 {@code setTenantIds(...)} 在 {@code SysUserServiceImpl#getUsersPage}（管理端用户分页），
	 * <b>不在 {@code findUserInfo()} 这条路径上</b> —— 也就是说经这 4 个契约方法拿到的值<b>恒为 null</b>
	 * （2026-09-14 核实）。
	 * 仍然保留，并且用可空的 {@code List}（而不是让 null 塌缩成空列表）：这看起来是个既有的功能缺口，
	 * 哪天有人把它补上，字段位置得先留着，否则又要破坏性地加回来。
	 */
	private List<String> tenantIds;

	/**
	 * 锁定标记。⚠️ <b>不是数据库原始列</b>：{@code SysUserController#setUserLockFlag} 会结合
	 * 租户状态（锁定 / 欠费到期）与店铺状态改写它，那段业务规则必须留在服务端。
	 * 这里承载的是<b>计算之后</b>的值。
	 */
	private String lockFlag;

	/** '1' = 下次登录前必须修改密码；鉴权链路映射成「凭证过期」，登录不发令牌。 */
	private String passwordChangeRequired;
}
