package com.zhiflow.upms.admin.service.impl;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.data.tenant.TenantContextManager;
import com.zhiflow.upms.admin.service.SysMenuService;
import com.zhiflow.upms.common.dto.MenuTree;
import com.zhiflow.upms.common.util.TreeUtil;
import com.zhiflow.upms.common.vo.MenuVO;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「权限列表」与「当前用户菜单树」的派生快照：按 (租户, 角色列表) 缓存<b>最终结果</b>。
 *
 * <p><b>为什么</b>：{@code /upms/user/info} 与 {@code /upms/menu} 原先只缓存「单个角色的全部菜单行」，
 * 每个请求都要从 Redis 读回并反序列化整份 {@code List<MenuVO>}——超管是 1731 个 MenuVO（约 590KB，
 * 每个带两个 LocalDateTime），两个接口各一次；2026-09-30 压测线程转储里这两个接口 68% 的忙碌栈停在这里。
 * 快照命中后只需读回权限字符串（超管约 850 个）或菜单树节点（几百个，不带时间字段）。
 *
 * <p><b>开关</b> {@value #ENABLED}（默认 false），每次调用现读 {@link Environment}，配置改完即时生效、
 * 不用重启。关着时两个接口的代码路径与改前逐行相同，本类不参与。
 *
 * <p><b>放在同一个缓存名 {@code public:menu_cache} 下</b>（键 {@code perm:…} / {@code tree:…}）：
 * <ul>
 *   <li>现有的整体清空点（菜单增删改、保存角色菜单、同步租户套餐、删角色）都按 {@code public:menu_cache::*}
 *       删除，运维手工按这个前缀删也一样——新键一起被清，不需要新增任何失效点。
 *       换个缓存名单独设 TTL（如 {@code public:menu_cache:snap#600}）会让这些失效点全部管不到它；</li>
 *   <li>用户换角色不需要失效：角色列表就是键的一部分；</li>
 *   <li>TTL 就是这个缓存名的全局 2 小时。</li>
 * </ul>
 *
 * <p><b>键里的角色列表按调用方给的原始顺序拼接，不排序。</b>权限数组的顺序是 {@code HashSet} 的迭代序，
 * 同一组角色换个顺序插入，哈希碰撞桶里的先后就可能不同。按原顺序做键，快照与原算法对同一输入才逐字节相同；
 * 代价只是同一组角色顺序不同时多一个键。
 *
 * <p><b>必须是独立 bean</b>：本类经注入的 {@link SysMenuService} 代理调 {@code findMenuByRoleId} 才会走基础缓存，
 * 调用方经本类的代理调用才会走快照缓存。两处都是自调用就会静默绕过缓存。
 *
 * <p><b>返回值一律是 {@link ArrayList}</b>：缓存序列化器按 NON_FINAL 写类型头，{@code ["java.util.ArrayList",[…]]}
 * 是基础缓存 {@code List<MenuVO>} 在生产上早已验证过的往返形态，别换成 {@code List.of} 之类的实现类。
 * 尤其不能缓存 {@code String[]}：数组是 final 类型、不写类型头，读回时第一个元素被当成类型名，反序列化失败
 * （{@code DefensiveRedisCache} 会删键当未命中，等于每次重算还刷 warn）。
 *
 * <p>两个方法都在参数给定的租户里计算，保证「键里的租户」与「查询按哪个租户过滤」是同一个值。
 */
@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class MenuSnapshotService {

	static final String ENABLED = "zhiflow.perf.menu-snapshot.enabled";

	static final String PERMISSIONS_KEY =
			"'perm:' + #tenantId + ':' + T(java.lang.String).join(',', #roleIds)";

	static final String USER_MENU_TREE_KEY =
			"'tree:' + #tenantId + ':' + T(java.lang.String).join(',', #roleIds)";

	private final SysMenuService sysMenuService;

	private final Environment environment;

	public boolean enabled() {
		try {
			return Boolean.TRUE.equals(environment.getProperty(ENABLED, Boolean.class, false));
		}
		catch (RuntimeException ex) {
			// 值写错时按关处理，避免 /upms/menu、/upms/user/info 全部 500
			log.warn("{} 的值无法解析为布尔，按关闭处理: {}", ENABLED, ex.toString());
			return false;
		}
	}

	/**
	 * 与 {@code SysUserServiceImpl#findUserInfo} 里的原算法逐行相同；返回顺序即 {@code HashSet} 的迭代序。
	 */
	@Cacheable(value = CacheConstants.MENU_CACHE, key = PERMISSIONS_KEY, unless = "#result == null")
	public List<String> permissions(String tenantId, List<String> roleIds) {
		return TenantContextManager.executeWithTenant(tenantId, () -> {
			Set<String> permissions = new HashSet<>();
			roleIds.forEach(roleId -> {
				List<String> permissionList = sysMenuService.findMenuByRoleId(roleId)
						.stream()
						.filter(menuVo -> StrUtil.isNotEmpty(menuVo.getPermission()))
						.map(MenuVO::getPermission)
						.collect(Collectors.toList());
				permissions.addAll(permissionList);
			});
			return new ArrayList<>(permissions);
		});
	}

	/**
	 * 与 {@code SysMenuController#getUserMenu} 里的原算法逐行相同。
	 */
	@Cacheable(value = CacheConstants.MENU_CACHE, key = USER_MENU_TREE_KEY, unless = "#result == null")
	public List<MenuTree> userMenuTree(String tenantId, List<String> roleIds) {
		return TenantContextManager.executeWithTenant(tenantId, () -> {
			Set<MenuVO> all = new HashSet<>();
			roleIds.forEach(roleId -> all.addAll(sysMenuService.findMenuByRoleId(roleId)));
			List<MenuTree> menuTreeList = all.stream()
					.filter(menuVo -> CommonConstants.MENU.equals(menuVo.getType()))
					.map(MenuTree::new)
					.sorted(Comparator.comparingInt(MenuTree::getSort))
					.collect(Collectors.toList());
			return new ArrayList<>(TreeUtil.buildOptimized(menuTreeList, CommonConstants.PARENT_ID));
		});
	}
}
