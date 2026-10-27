package com.zhiflow.upms.admin.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.data.cache.AfterCommitCacheEviction;
import com.zhiflow.upms.admin.service.SysMenuService;
import com.zhiflow.upms.common.entity.SysMenu;
import com.zhiflow.upms.common.entity.SysRoleMenu;
import com.zhiflow.upms.common.vo.MenuVO;
import com.zhiflow.upms.admin.mapper.SysMenuMapper;
import com.zhiflow.upms.admin.mapper.SysRoleMenuMapper;
import com.zhiflow.common.core.util.R;
import lombok.AllArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.core.constant.CommonConstants;
import java.util.List;

/**
 * <p>
 * 菜单权限表 服务实现类
 * </p>
 *
 * @author
 */
@Service
@AllArgsConstructor
public class SysMenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements SysMenuService {

	/**
	 * {@link #findMenuByRoleId} 的缓存键：<b>当前上下文租户</b> + 角色。
	 *
	 * <p>原键只有 {@code roleId + '_menu'}，而查询 {@code listMenusByRoleId} 里的 {@code sys_role_menu}
	 * 是租户表，租户插件按<b>当前上下文</b>过滤——同一个角色在不同上下文里查出来的行不同，却共用一个键，
	 * 2 小时过期后谁先来查缓存里就存谁那份。库里 7 个角色在别的租户下也有 {@code sys_role_menu} 行；
	 * 超管带 {@code switch-tenant-id} 查自己的角色时拿到的是空列表，会把自己的侧边栏和 846 个权限一起清空 2 小时。
	 * 上下文为空（不过滤、查全部租户）时键里是 {@code null}，同样与其它租户隔开。
	 */
	static final String ROLE_MENU_CACHE_KEY =
			"T(com.zhiflow.common.data.tenant.TenantContextHolder).getTenantId() + ':' + #roleId + '_menu'";

	private final SysRoleMenuMapper sysRoleMenuMapper;
	private final CacheManager cacheManager;

	@Override
	@Cacheable(value = CacheConstants.MENU_CACHE, key = ROLE_MENU_CACHE_KEY, unless = "#result == null")
	public List<MenuVO> findMenuByRoleId(String roleId) {
		return baseMapper.listMenusByRoleId(roleId);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public R removeMenuById(String id) {
		// 查询父节点为当前节点的节点；只算未软删的下级，否则只剩软删子菜单的父菜单会被永久拒删
		// （读路径 SysMenuMapper.xml 同样按 del_flag='0' 过滤，界面上看不到那些子菜单）
		List<SysMenu> menuList = this.list(Wrappers.<SysMenu>query()
				.lambda().eq(SysMenu::getParentId, id)
				.eq(SysMenu::getDelFlag, CommonConstants.STATUS_NORMAL));
		if (CollUtil.isNotEmpty(menuList)) {
			return R.failed("菜单含有下级不能删除");
		}
		//删除角色菜单关联
		sysRoleMenuMapper.delete(Wrappers.<SysRoleMenu>query()
						.lambda().eq(SysRoleMenu::getMenuId, id));

		//删除当前菜单及其子菜单
		R result = R.ok(this.removeById(id));
		// 事务提交后再清（见 AfterCommitCacheEviction）；原 @CacheEvict 与 @Transactional 谁先谁后没有保证。
		// 用户缓存也要清：BaseUser 的权限码来自菜单，删菜单后不清 user_cache，已登录用户最长 2 小时（TTL）
		// 还带着被删菜单的权限码。顺序先菜单后用户（理由见 AfterCommitCacheEviction#clear）。
		AfterCommitCacheEviction.clear(cacheManager, CacheConstants.MENU_CACHE, CacheConstants.USER_CACHE);
		return result;
	}

	@Override
	public Boolean updateMenuById(SysMenu sysMenu) {
		Boolean updated = this.updateById(sysMenu);
		// 本方法不开事务：updateById 已自动提交，这里当场清；被外层事务包住时则推迟到外层提交后。
		// 改菜单（典型是改权限码）同样要清用户缓存，否则新权限码最长 2 小时后才对已登录用户生效。
		// 新增菜单（saveMenu）还没挂到任何角色上，不影响谁的权限，不清用户缓存。
		AfterCommitCacheEviction.clear(cacheManager, CacheConstants.MENU_CACHE, CacheConstants.USER_CACHE);
		return updated;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void saveMenu(SysMenu entity) {
		super.save(entity);
		AfterCommitCacheEviction.clear(cacheManager, CacheConstants.MENU_CACHE);
	}
}
