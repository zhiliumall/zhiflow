package com.zhiflow.upms.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * {@code UserApi#getInsideUserList} 的响应投影 —— 取代把 {@code SysUser} 实体当契约。
 *
 * <p>字段 = {@code SysUserMapper.xml#listSysUser} 的 SELECT 列表，减去
 * {@code password} 与 {@code salt}（那条 SQL 把它们<b>硬写成 {@code null}</b>，
 * 列名旁边就写着 {@code null password, null salt}，所以带过来也永远是 null）。
 *
 * <p>本仓 Java 侧只读 {@code phone}（订单短信通知拼号码串）、{@code id}。但 mall-api 的
 * {@code /customerservice/list} 是把整行交给前端的公开端点（H5 里那个 API 函数已无人调用，
 * PC / App 核不到），所以按「SQL 真的 SELECT 了什么」全带 —— 口径同 {@link AreaInfo}。
 */
@Data
public class SysUserInfo implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private String id;

	private String username;

	private String email;

	private String phone;

	private String avatar;

	private String organId;

	private String tenantId;

	/** 账号类型：'-1' 系统管理员 '1' 商城管理员。 */
	private String type;

	private String nickName;

	private String lockFlag;

	private String delFlag;

	private LocalDateTime createTime;

	private LocalDateTime updateTime;
}
