package com.zhiflow.upms.admin.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.data.cache.AfterCommitCacheEviction;
import com.zhiflow.upms.common.entity.SysRoleMenu;
import com.zhiflow.upms.admin.mapper.SysRoleMenuMapper;
import com.zhiflow.upms.admin.service.SysRoleMenuService;
import lombok.AllArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.zhiflow.common.core.constant.CacheConstants;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 角色菜单表 服务实现类
 * </p>
 *
 * @author
 */
@Service
@AllArgsConstructor
public class SysRoleMenuServiceImpl extends ServiceImpl<SysRoleMenuMapper, SysRoleMenu> implements SysRoleMenuService {
	private final CacheManager cacheManager;

	/**
	 * @param role
	 * @param roleId  角色
	 * @param menuIds 菜单ID拼成的字符串，每个id之间根据逗号分隔
	 * @return
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean saveRoleMenus(String role, String roleId, String menuIds) {
		this.remove(Wrappers.<SysRoleMenu>query().lambda()
				.eq(SysRoleMenu::getRoleId, roleId));

		// 清菜单缓存（含派生的 perm:/tree: 快照）与 userinfo。两处位置都是有意的：
		// 1. 必须在下面的提前 return 之前——menuIds 为空时上面已经把这个角色的菜单删光了，
		//    原先 return 在 clear 之前，只清了 {roleId}_menu 一个键，权限与其它派生键最长 2 小时不变；
		// 2. 必须等事务提交后再清——原先在 saveBatch 之前当场清，清完到提交之间进来的请求会把旧数据
		//    重新写回缓存并保留 2 小时（见 AfterCommitCacheEviction）。
		// 原方法上按 {roleId}_menu 清单键的 @CacheEvict 已去掉：键已改成带租户的格式，对不上了，且整体清空已覆盖它。
		AfterCommitCacheEviction.clear(cacheManager, CacheConstants.MENU_CACHE, CacheConstants.USER_CACHE);

		if (StrUtil.isBlank(menuIds)) {
			return Boolean.TRUE;
		}
		List<SysRoleMenu> roleMenuList = Arrays
				.stream(menuIds.split(","))
				.map(menuId -> {
					SysRoleMenu roleMenu = new SysRoleMenu();
					roleMenu.setRoleId(roleId);
					roleMenu.setMenuId(menuId);
					return roleMenu;
				}).collect(Collectors.toList());

		return this.saveBatch(roleMenuList);
	}
}
