package com.zhiflow.upms.common.dto;

import com.zhiflow.upms.common.entity.SysUser;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * @author
 */
@Data
public class UserDTO extends SysUser {
	/**
	 * 角色ID
	 */
	private List<String> roleIds;

	/**
	 * 角色筛选：分页查询时按单个角色ID过滤（可选）
	 */
	private String roleId;

	/**
	 * 店铺ID
	 */
	private String shopId;

	private String organId;

	/**
	 * 新密码
	 */
	private String newpassword1;
	/**
	 * 验证码
	 */
	private String code;
	/**
	 * 操作类型
	 */
	private String doType;
	/**
	 * 平台系统管理员绑定的租户
	 */
	private List<String> tenantIds;
}
