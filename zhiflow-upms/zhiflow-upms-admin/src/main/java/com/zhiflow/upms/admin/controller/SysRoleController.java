package com.zhiflow.upms.admin.controller;

import com.zhiflow.upms.admin.projection.UpmsInsideProjections;
import com.zhiflow.upms.common.dto.RoleDataScope;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.security.annotation.Inside;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.common.security.util.SecurityUtils;
import com.zhiflow.upms.admin.service.SysRoleMenuService;
import com.zhiflow.upms.admin.service.SysRoleService;
import com.zhiflow.upms.common.entity.SysRole;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.upms.common.entity.SysRoleMenu;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;

/**
 * @author
 */
@RestController
@AllArgsConstructor
@RequestMapping("/role")
@Tag(description = "role", name = "角色管理模块")
public class SysRoleController {
	private final SysRoleService sysRoleService;
	private final SysRoleMenuService sysRoleMenuService;

	/**
	 * 通过ID查询角色信息
	 *
	 * @param id ID
	 * @return 角色信息
	 */
	@Operation(summary = "通过ID查询角色信息")
	@GetMapping("/{id}")
	@PreAuthorize("@ato.hasAuthority('sys:role:get')")
	public R getById(@PathVariable String id) {
		return R.ok(sysRoleService.getById(id));
	}

	/**
	 * 添加角色
	 *
	 * @param sysRole 角色信息
	 * @return ok、false
	 */
	@Operation(summary = "添加角色")
	@SysLog("添加角色")
	@PostMapping
	@PreAuthorize("@ato.hasAuthority('sys:role:add')")
	public R save(@Valid @RequestBody SysRole sysRole) {
		return R.ok(sysRoleService.save(sysRole));
	}

	/**
	 * 修改角色
	 *
	 * @param sysRole 角色信息
	 * @return ok/false
	 */
	@Operation(summary = "修改角色")
	@SysLog("修改角色")
	@PutMapping
	@PreAuthorize("@ato.hasAuthority('sys:role:edit')")
	public R update(@Valid @RequestBody SysRole sysRole) {
		if(!this.judeAdmin(sysRole.getId())){
			return R.failed("管理员角色不能操作");
		}
		return R.ok(sysRoleService.updateById(sysRole));
	}

	/**
	 * 删除角色
	 *
	 * @param id
	 * @return
	 */
	@Operation(summary = "删除角色")
	@SysLog("删除角色")
	@DeleteMapping("/{id}")
	@PreAuthorize("@ato.hasAuthority('sys:role:del')")
	public R removeById(@PathVariable String id) {
		if(!this.judeAdmin(id)){
			return R.failed("管理员角色不能操作");
		}
		return R.ok(sysRoleService.removeRoleById(id));
	}

	/**
	 * 获取角色列表
	 *
	 * @return 角色列表
	 */
	@Operation(summary = "获取角色列表")
	@GetMapping("/list")
	public List<SysRole> getList(SysRole sysRole) {
		return sysRoleService.list(Wrappers.query(sysRole));
	}

	/**
	 * 分页查询角色信息
	 *
	 * @param page 分页对象
	 * @return 分页对象
	 */
	@Operation(summary = "分页查询角色信息")
	@GetMapping("/page")
	@PreAuthorize("@ato.hasAuthority('sys:role:index')")
	public R getRolePage(Page page) {
		return R.ok(sysRoleService.page(page, Wrappers.emptyWrapper()));
	}

	/**
	 * 更新角色菜单
	 *
	 * @param sysRoleMenu roleId  角色ID
	 * @param sysRoleMenu menuId 菜单ID拼成的字符串，每个id之间根据逗号分隔
	 * @return ok、false
	 */
	@Operation(summary = "更新角色菜单")
	@SysLog("更新角色菜单")
	@PutMapping("/menu")
	@PreAuthorize("@ato.hasAuthority('sys:role:perm')")
	public R saveRoleMenus(@RequestBody SysRoleMenu sysRoleMenu) {
		String roleId = sysRoleMenu.getRoleId();
		String menuIds = sysRoleMenu.getMenuId();
		if(StrUtil.isBlank(roleId) || StrUtil.isBlank(menuIds)){
			return R.ok();
		}
		if(!this.judeAdmin(roleId)){
			return R.failed("管理员角色不能操作");
		}
		SysRole sysRole = sysRoleService.getById(roleId);
		return R.ok(sysRoleMenuService.saveRoleMenus(sysRole.getRoleCode(), roleId, menuIds));
	}

	/**
	 * 校验是否是管理员角色
	 * @param roleId
	 * @return
	 */
	boolean judeAdmin(String roleId){
		SysRole sysRole = sysRoleService.getById(roleId);
		//租户管理员
		if(CommonConstants.ROLE_CODE_ADMIN.equals(sysRole.getRoleCode())){
			BaseUser user = SecurityUtils.getUser();
			//只有系统管理员能操作其权限
			if(StrUtil.equals(CommonConstants.USER_TYPE_S, user.getType())){
				return Boolean.TRUE;
			}
			return Boolean.FALSE;
		}
		return Boolean.TRUE;
	}

	/**
	 * 通过角色ID查询权限类型最小的角色
	 *
	 * @param roleIdList
	 * @return R
	 */
	@Inside
	@PostMapping("/dstype/min")
	public R<RoleDataScope> getDsTypeMinRole(@RequestBody List<String> roleIdList){
		return R.ok(UpmsInsideProjections.roleDataScope(sysRoleService.listByIds(roleIdList).stream()
				.min(Comparator.comparingInt(SysRole::getDsType)).orElse(null)));
	}
}
