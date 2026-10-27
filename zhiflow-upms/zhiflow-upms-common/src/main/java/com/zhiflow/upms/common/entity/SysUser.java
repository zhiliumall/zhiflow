package com.zhiflow.upms.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.zhiflow.common.core.entity.base.TenantEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * <p>
 * 用户表
 * </p>
 *
 * @author
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description="用户")
public class SysUser extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

	/**
	 * 用户名
	 */
	@Schema(description = "用户名")
	private String username;

	/**
	 * 邮箱
	 */
	@Schema(description = "邮箱")
	private String email;

	@Schema(description = "密码")
	private String password;
	/**
	 * 随机盐
	 */
	@Schema(description = "随机盐")
	@JsonIgnore
	private String salt;

	/**
	 * 锁定标记
	 */
	@Schema(description = "锁定标记")
	private String lockFlag;

	/**
	 * '1' = 下次登录前必须修改密码（首启随机口令、后台重置口令之后）
	 */
	@Schema(description = "下次登录前必须修改密码：1 是 0 否")
	private String passwordChangeRequired;

	/**
	 * 手机号
	 */
	@Schema(description = "手机号")
	private String phone;
	/**
	 * 头像
	 */
	@Schema(description = "头像")
	private String avatar;

	/**
	 * 机构ID
	 */
	@Schema(description = "机构ID")
	private String organId;

	/**
	 * 账号类型：-1、系统管理员；1、商城管理员
	 */
	@Schema(description = "账号类型：-1、系统管理员；1、商城管理员")
	private String type;

	/**
	 * 昵称（客服系统中展示的名称）
	 */
	@Schema(description = "昵称（客服系统中展示的名称）")
	private String nickName;

	/**
	 * 平台系统管理员绑定的租户
	 */
	@TableField(exist = false)
	private List<String> tenantIds;
}
