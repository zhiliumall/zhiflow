package com.zhiflow.upms.admin.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.data.tenant.TenantContextManager;
import com.zhiflow.common.data.tenant.annotation.IgnoreTenant;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.upms.admin.service.SysMenuService;
import com.zhiflow.upms.admin.service.impl.MenuSnapshotService;
import com.zhiflow.upms.common.dto.MenuTree;
import com.zhiflow.upms.common.entity.SysMenu;
import com.zhiflow.upms.common.vo.MenuVO;
import com.zhiflow.upms.common.util.TreeUtil;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.common.security.util.SecurityUtils;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * @date 2019/07/11
 */
@RestController
@AllArgsConstructor
@RequestMapping("/menu")
@Tag(description = "menu", name = "菜单管理模块")
public class SysMenuController {
	private final SysMenuService sysMenuService;
	private final MenuSnapshotService menuSnapshotService;

	/**
	 * 获取所有菜单和权限
	 *
	 * @return 树形菜单
	 */
	@Operation(summary = "返回树形菜单集合")
	@GetMapping(value = "/all/tree")
	@Cacheable(value = CacheConstants.MENU_CACHE, key = "'all_tree'")
	public R getAllTree() {
		List<MenuTree> menuTreeList = TreeUtil.buildTreeOptimized(sysMenuService.list(Wrappers.<SysMenu>lambdaQuery()
				.orderByAsc(SysMenu::getSort)).stream()
				.collect(Collectors.toList()),CommonConstants.PARENT_ID);
		return R.ok(menuTreeList);
	}

	/**
	 * 获取当前用户的菜单
	 *
	 * @return 当前用户的树形菜单
	 */
	@Operation(summary = "返回当前用户的树形菜单集合")
	@GetMapping
	public R getUserMenu() {
		List<String> roleIds = SecurityUtils.getRoles();
		return inHomeTenant(() -> {
			// 开关打开时取 (租户, 角色列表) 的菜单树快照；与下面的原算法对同一输入逐字节相同（见 MenuSnapshotService）
			if (menuSnapshotService.enabled()) {
				return R.ok(menuSnapshotService.userMenuTree(TenantContextHolder.getTenantId(), roleIds));
			}
			// 获取符合条件的菜单
			Set<MenuVO> all = new HashSet<>();
			roleIds.forEach(roleId -> all.addAll(sysMenuService.findMenuByRoleId(roleId)));
			List<MenuTree> menuTreeList = all.stream()
					.filter(menuVo -> CommonConstants.MENU.equals(menuVo.getType()))
					.map(MenuTree::new)
					.sorted(Comparator.comparingInt(MenuTree::getSort))
					.collect(Collectors.toList());
			return R.ok(TreeUtil.buildOptimized(menuTreeList, CommonConstants.PARENT_ID));
		});
	}

	/**
	 * 获取当前用户的菜单和权限
	 *
	 * @return 树形菜单
	 */
	@Operation(summary = "返回树形菜单集合")
	@GetMapping(value = "/tree")
	public R getTree() {
		return inHomeTenant(() -> {
			Set<MenuVO> all = new HashSet<>();
			SecurityUtils.getRoles()
					.forEach(roleId -> all.addAll(sysMenuService.findMenuByRoleId(roleId)));
			List<MenuTree> menuTreeList = all.stream()
					.map(MenuTree::new)
					.collect(Collectors.toList());
			return R.ok(TreeUtil.buildOptimized(menuTreeList, CommonConstants.PARENT_ID));
		});
	}

	/**
	 * 在当前用户<b>自己的</b>租户里执行（与 {@code SysUserController#info} 同一写法）。
	 *
	 * <p>当前用户的角色、角色的 {@code sys_role_menu} 行都属于用户自己的租户；而超管带
	 * {@code switch-tenant-id} 时 {@code TenantContextHolderFilter} 会把上下文换成目标租户——
	 * 后台前端对超管的 {@code /upms/menu} 恰恰带这个头（没选租户时默认 "1"）。换过去再按角色查菜单，
	 * {@code sys_role_menu} 按目标租户过滤，超管自己的角色在那里没有行，查出来是空的。
	 * 非超管的上下文本来就是自己的租户，这里是空操作。
	 *
	 * <p>拿不到 {@link BaseUser} 或它没有租户号时保持当前上下文不动（不能 set 成 null——null 上下文等于不按租户过滤）。
	 */
	private static <T> T inHomeTenant(Supplier<T> body) {
		BaseUser user = SecurityUtils.getUser();
		String homeTenantId = user == null ? null : user.getTenantId();
		if (StrUtil.isBlank(homeTenantId)) {
			return body.get();
		}
		return TenantContextManager.executeWithTenant(homeTenantId, body);
	}

	/**
	 * 返回角色的菜单集合
	 *
	 * @param roleId 角色ID
	 * @return 属性集合
	 */
	@Operation(summary = "返回角色的菜单集合")
	@GetMapping("/tree/{roleId}")
	public R getRoleTree(@PathVariable String roleId) {
		return R.ok(sysMenuService.findMenuByRoleId(roleId)
				.stream()
				.map(MenuVO::getId)
				.collect(Collectors.toList()));
	}

	/**
	 * 通过ID查询菜单的详细信息
	 *
	 * @param id 菜单ID
	 * @return 菜单详细信息
	 */
	@Operation(summary = "通过ID查询菜单的详细信息")
	@GetMapping("/{id}")
	@PreAuthorize("@ato.hasAuthority('sys:menu:get')")
	public R getById(@PathVariable String id) {
		return R.ok(sysMenuService.getById(id));
	}

	/**
	 * 新增菜单
	 *
	 * @param sysMenu 菜单信息
	 * @return ok/false
	 */
	@IgnoreTenant
	@Operation(summary = "新增菜单")
	@SysLog("新增菜单")
	@PostMapping
	@PreAuthorize("@ato.hasAuthority('sys:menu:add')")
	public R save(@Valid @RequestBody SysMenu sysMenu) {
		sysMenuService.saveMenu(sysMenu);
		return R.ok();
	}

	/**
	 * 删除菜单
	 *
	 * @param id 菜单ID
	 * @return ok/false
	 */
	@IgnoreTenant
	@Operation(summary = "删除菜单")
	@SysLog("删除菜单")
	@DeleteMapping("/{id}")
	@PreAuthorize("@ato.hasAuthority('sys:menu:del')")
	public R removeById(@PathVariable String id) {
		return sysMenuService.removeMenuById(id);
	}

	/**
	 * 更新菜单
	 *
	 * @param sysMenu
	 * @return
	 */
	@Operation(summary = "更新菜单")
	@SysLog("更新菜单")
	@PutMapping
	@PreAuthorize("@ato.hasAuthority('sys:menu:edit')")
	public R update(@Valid @RequestBody SysMenu sysMenu) {
		sysMenu.setUpdateTime(LocalDateTime.now());
		return R.ok(sysMenuService.updateMenuById(sysMenu));
	}
}
