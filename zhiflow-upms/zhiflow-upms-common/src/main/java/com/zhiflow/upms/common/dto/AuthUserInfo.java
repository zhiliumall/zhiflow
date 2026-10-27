package com.zhiflow.upms.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * {@code UserApi#info} 的投影 —— 取代把 {@code UserInfo}（内含 {@code SysUser} 实体）当契约。
 *
 * <p>字段形状刻意与老的 {@code UserInfo} 保持同构（{@code String[]} 而不是 {@code List}），
 * 好让唯一的消费方 {@code BaseUserDetailsServiceImpl#getUserDetails} 只换类型、不换写法 ——
 * 鉴权链路上每多改一行都是一次登录故障的机会。
 *
 * @see AuthUserIdentity 「一个都不能少」的字段清单与推导
 */
@Data
public class AuthUserInfo implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	/** 角色 ID 数组（不是角色 code / 名称）。拼成 {@code ROLE_<id>} 授权。 */
	private String[] roles;

	/** 权限标识数组，来自「角色 → 菜单」的 {@code permission} 列。 */
	private String[] permissions;

	/** 用户身份。查不到用户时整个响应是 {@code R.ok(null)}，不会是「有 info 但 user 为 null」。 */
	private AuthUserIdentity user;

}
