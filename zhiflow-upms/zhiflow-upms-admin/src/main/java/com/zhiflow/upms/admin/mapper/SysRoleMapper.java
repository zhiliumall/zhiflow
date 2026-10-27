package com.zhiflow.upms.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.upms.common.entity.SysRole;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * Mapper 接口
 * </p>
 *
 * @author
 */
public interface SysRoleMapper extends BaseMapper<SysRole> {
	/**
	 * 通过用户ID，查询角色信息
	 *
	 * @param userId
	 * @return
	 */
	List<String> listRoleIdsByUserId(String userId);

	/**
	 * C-09：批量查询多个用户的角色ID（消除用户分页 N+1）
	 *
	 * @param userIds 用户ID列表
	 * @return 每行含 userId / roleId
	 */
	List<Map<String, Object>> listUserRolesByUserIds(@Param("userIds") List<String> userIds);

	/**
	 * 批量查询多个用户的角色明细（用于用户分页 roleList 装配，单条 SQL 无 N+1）
	 *
	 * @param userIds 用户ID列表
	 * @return 每行含 userId 及角色字段 roleId/roleName/roleCode/roleDesc
	 */
	List<Map<String, Object>> listUserRolesDetailByUserIds(@Param("userIds") List<String> userIds);
}
