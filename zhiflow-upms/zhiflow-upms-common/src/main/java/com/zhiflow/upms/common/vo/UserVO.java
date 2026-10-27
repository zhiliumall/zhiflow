package com.zhiflow.upms.common.vo;

import com.zhiflow.upms.common.entity.SysRole;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * @author
 */
@Data
public class UserVO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

	/**
	 * 主键ID
	 */
	private String id;
	/**
	 * 用户名
	 */
	private String username;
	/**
	 * 邮箱
	 */
	private String email;
	/**
	 * 密码
	 */
	private String password;
	/**
	 * 随机盐
	 */
	private String salt;

	/**
	 * 创建时间
	 */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private LocalDateTime createTime;
	/**
	 * 修改时间
	 */
	private LocalDateTime updateTime;
	/**
	 * 0-正常，1-删除
	 */
	private String delFlag;

	/**
	 * 锁定标记
	 */
	private String lockFlag;
	/**
	 * 简介
	 */
	private String phone;
	/**
	 * 头像
	 */
	private String avatar;

	/**
	 * 机构ID
	 */
	private String organId;

	/**
	 * 租户ID
	 */
	private String tenantId;

	/**
	 * 账号类型：-1、系统管理员；1、商城管理员
	 */
	private String type;
	/**
	 * 昵称
	 */
	private String nickName;

	/**
	 * 角色列表
	 */
	private List<SysRole> roleList;

	/**
	 * 角色id
	 */
	private List<String> roleIds;

	/**
	 * 平台系统管理员绑定的租户
	 */
	private List<String> tenantIds;

}
