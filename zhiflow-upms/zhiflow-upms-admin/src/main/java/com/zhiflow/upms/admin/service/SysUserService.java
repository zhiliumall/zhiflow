package com.zhiflow.upms.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.upms.common.dto.UserDTO;
import com.zhiflow.upms.common.dto.UserInfo;
import com.zhiflow.upms.common.entity.SysUser;
import com.zhiflow.upms.common.vo.UserVO;

import java.util.Map;

/**
 * @author
 */
public interface SysUserService extends IService<SysUser> {
	/**
	 * 查询用户信息
	 *
	 * @param sysUser 用户
	 * @return userInfo
	 */
	UserInfo findUserInfo(SysUser sysUser);

	/**
	 * 分页查询用户信息（含有角色信息）
	 *
	 * @param page    分页对象
	 * @param userDTO 参数列表
	 * @return
	 */
	IPage getUsersPage(Page page, UserDTO userDTO);

	/**
	 * 员工数量统计（在职/子账号），作用域与 {@link #getUsersPage} 一致
	 *
	 * @param userDTO 与分页一致的查询条件
	 * @return 含 total（子账号=作用域内员工总数）/ active（在职）两个计数
	 */
	Map<String, Object> getStaffCount(UserDTO userDTO);

	/**
	 * 删除用户
	 *
	 * @param sysUser 用户
	 * @return boolean
	 */
	Boolean deleteUserById(SysUser sysUser);

	/**
	 * 更新当前用户基本信息
	 *
	 * @param userDto 用户信息
	 * @return Boolean
	 */
	Boolean updateUserInfo(UserDTO userDto);

	/**
	 * 更新指定用户信息
	 *
	 * @param userDto 用户信息
	 * @return
	 */
	Boolean updateUser(UserDTO userDto);

	/**
	 * 通过ID查询用户信息
	 *
	 * @param id 用户ID
	 * @return 用户信息
	 */
	UserVO selectUserVoById(String id);

	/**
	 * 保存用户信息
	 *
	 * @param userDto DTO 对象
	 * @return ok/fail
	 */
	Boolean saveUser(UserDTO userDto);

	/**
	 * 无租户查询
	 *
	 * @param sysUser
	 * @return SysUser
	 */
	SysUser getByNoTenant(SysUser sysUser);

	/**
	 * 绑定/解绑手机号
	 * @param userDto
	 */
	void bindPhone(UserDTO userDto);
}
