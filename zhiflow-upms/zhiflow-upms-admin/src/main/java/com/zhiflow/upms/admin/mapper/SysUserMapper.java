package com.zhiflow.upms.admin.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.upms.common.dto.UserDTO;
import com.zhiflow.upms.common.entity.SysUser;
import com.zhiflow.upms.common.vo.UserVO;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 用户表 Mapper 接口
 * </p>
 *
 * @author
 */
public interface SysUserMapper extends BaseMapper<SysUser> {
	/**
	 * 无租户查询
	 *
	 * @param sysUser
	 * @return SysUser
	 */
	@InterceptorIgnore(tenantLine="true")
	SysUser getByNoTenant(SysUser sysUser);

	/**
	 * 数量查询
	 * @param sysUser
	 * @return
	 */
	@InterceptorIgnore(tenantLine="true")
	Integer selectCountNoTenant(SysUser sysUser);

	/**
	 * 分页查询用户信息（含角色）
	 *
	 * @param page      分页
	 * @param userDTO   查询参数
	 * @return list
	 */
	IPage<List<UserVO>> getUserVosPage(Page page, @Param("query") UserDTO userDTO);

	/**
	 * 通过ID查询用户信息
	 *
	 * @param id 用户ID
	 * @return userVo
	 */
	UserVO getUserVoById(String id);

	/**
	 * 员工数量统计（在职/子账号）。作用域与 {@link #getUserVosPage} 完全一致
	 * （type/username/roleId 等条件 + 租户插件注入 tenant_id），保证统计口径与列表吻合。
	 *
	 * @param userDTO 与分页一致的查询条件
	 * @return 含 total（作用域内员工总数=子账号）/ active（lock_flag='0' 在职）两个计数
	 */
	Map<String, Object> getStaffCount(@Param("query") UserDTO userDTO);
	/**
	 * 查询用户List，不包含敏感数据
	 *
	 * @return SysUser
	 */
	List<SysUser> listSysUser(@Param("query") SysUser sysUser);
}
