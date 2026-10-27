package com.zhiflow.upms.admin.service.impl;

import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.data.tenant.TenantContextManager;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.upms.admin.cache.SerializedBytesCacheManager;
import com.zhiflow.upms.admin.controller.SysMenuController;
import com.zhiflow.upms.admin.mapper.SysMenuMapper;
import com.zhiflow.upms.admin.mapper.SysRoleMenuMapper;
import com.zhiflow.upms.admin.service.SysMenuService;
import com.zhiflow.upms.common.dto.MenuTree;
import com.zhiflow.upms.common.util.TreeUtil;
import com.zhiflow.upms.common.vo.MenuVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 角色菜单基础缓存的<b>租户隔离</b>，以及超管带 {@code switch-tenant-id} 取自己菜单的结果。
 *
 * <p>数据形状：
 * <ul>
 *   <li>超管 {@code system}（租户 -1）的角色 {@code 1} 只在 -1 下有 {@code sys_role_menu} 行；</li>
 *   <li>租户 1 管理员角色 {@code 1900000000000000006} 在租户 1 和 -1 下都有 {@code sys_role_menu} 行，且两边不同。</li>
 * </ul>
 * 映射器按「当前上下文租户」返回不同的行，模拟租户插件往 {@code sys_role_menu} 上加的过滤。
 *
 * <p>对照组 {@link LegacyKeyLookup} 用改前的键（不带租户），同一个场景下必须复现串值——
 * 否则这个测试设置本身就测不出问题。
 */
class MenuCacheTenantIsolationTest {

	static final String SUPER_ROLE = "1";
	static final String SUPER_ROLE_2 = "1900000000000000005";
	static final String TENANT1_ADMIN_ROLE = "1900000000000000006";

	private AnnotationConfigApplicationContext ctx;
	private SysMenuService sysMenuService;
	private LegacyKeyLookup legacy;
	private SerializedBytesCacheManager.BytesCache menuCache;

	@Configuration
	@EnableCaching(proxyTargetClass = true)
	static class Config {
		@Bean
		SerializedBytesCacheManager cacheManager() {
			return new SerializedBytesCacheManager();
		}

		@Bean
		SysMenuMapper sysMenuMapper() {
			SysMenuMapper mapper = mock(SysMenuMapper.class);
			when(mapper.listMenusByRoleId(anyString()))
					.thenAnswer(inv -> rowsVisibleIn(TenantContextHolder.getTenantId(), inv.getArgument(0)));
			return mapper;
		}

		@Bean
		SysRoleMenuMapper sysRoleMenuMapper() {
			return mock(SysRoleMenuMapper.class);
		}

		@Bean
		SysMenuServiceImpl sysMenuService(SysRoleMenuMapper sysRoleMenuMapper, CacheManager cacheManager) {
			return new SysMenuServiceImpl(sysRoleMenuMapper, cacheManager);
		}

		@Bean
		LegacyKeyLookup legacyKeyLookup(SysMenuMapper sysMenuMapper) {
			return new LegacyKeyLookup(sysMenuMapper);
		}
	}

	/** 对照组：与 findMenuByRoleId 相同，只是缓存键是改前那个（不带租户）。 */
	static class LegacyKeyLookup {
		private final SysMenuMapper mapper;

		LegacyKeyLookup(SysMenuMapper mapper) {
			this.mapper = mapper;
		}

		@Cacheable(value = CacheConstants.MENU_CACHE, key = "'legacy:' + #roleId  + '_menu'", unless = "#result == null")
		public List<MenuVO> findMenuByRoleId(String roleId) {
			return mapper.listMenusByRoleId(roleId);
		}
	}

	/** 仿租户插件：sys_role_menu 按当前上下文租户过滤；上下文为空时不过滤。 */
	static List<MenuVO> rowsVisibleIn(String tenantId, String roleId) {
		List<MenuVO> rows = new ArrayList<>();
		boolean superTenant = CommonConstants.SYSTEM_TENANT_ID.equals(tenantId) || tenantId == null;
		boolean tenant1 = "1".equals(tenantId) || tenantId == null;
		switch (roleId) {
			case SUPER_ROLE -> {
				if (superTenant) {
					rows.add(menu("100", "0", "0", "系统管理", 1, null));
					rows.add(menu("101", "100", "0", "用户管理", 2, "sys_user_view"));
					rows.add(menu("102", "101", "1", "新增用户", 1, "sys_user_add"));
				}
			}
			case SUPER_ROLE_2 -> {
				if (superTenant) {
					rows.add(menu("200", "0", "0", "订单", 3, null));
					rows.add(menu("201", "200", "1", "订单导出", 1, "mall_order_export"));
				}
			}
			case TENANT1_ADMIN_ROLE -> {
				if (tenant1) {
					rows.add(menu("300", "0", "0", "商品", 1, null));
					rows.add(menu("301", "300", "0", "商品列表", 1, "mall_goods_index"));
					rows.add(menu("302", "301", "1", "商品编辑", 1, "mall_goods_edit"));
				}
				if (superTenant) {
					rows.add(menu("399", "0", "0", "平台遗留行", 9, "legacy_platform_only"));
				}
			}
			default -> {
			}
		}
		return rows;
	}

	static MenuVO menu(String id, String parentId, String type, String name, int sort, String permission) {
		MenuVO vo = new MenuVO();
		vo.setId(id);
		vo.setParentId(parentId);
		vo.setType(type);
		vo.setName(name);
		vo.setSort(sort);
		vo.setPermission(permission);
		vo.setDelFlag("0");
		vo.setKeepAlive("0");
		return vo;
	}

	@BeforeEach
	void setUp() {
		ctx = new AnnotationConfigApplicationContext(Config.class);
		sysMenuService = ctx.getBean(SysMenuService.class);
		legacy = ctx.getBean(LegacyKeyLookup.class);
		menuCache = ctx.getBean(SerializedBytesCacheManager.class).getCache(CacheConstants.MENU_CACHE);
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
		SecurityContextHolder.clearContext();
		ctx.close();
	}

	private static List<String> ids(List<MenuVO> rows) {
		return rows.stream().map(MenuVO::getId).collect(Collectors.toList());
	}

	@Test
	@DisplayName("对照组：改前的键不带租户——先在租户 1 上下文里查超管角色（空），-1 上下文再查就拿到这份空列表")
	void controlSampleLegacyKeyLeaksAcrossTenants() {
		List<MenuVO> inTenant1 = TenantContextManager.executeWithTenant("1", () -> legacy.findMenuByRoleId(SUPER_ROLE));
		List<MenuVO> inHome = TenantContextManager.executeWithTenant("-1", () -> legacy.findMenuByRoleId(SUPER_ROLE));

		assertTrue(inTenant1.isEmpty(), "超管角色在租户 1 下没有 sys_role_menu 行");
		assertTrue(inHome.isEmpty(),
				"对照组必须复现串值：-1 上下文拿到的是租户 1 那份空列表（真出过这个问题：超管侧边栏和权限一起变空）");
	}

	@Test
	@DisplayName("新键带租户：同一角色在不同上下文各有各的缓存项，互不串值；重复查询命中缓存")
	void sameRoleInDifferentTenantsDoesNotShareAnEntry() {
		List<MenuVO> inTenant1 = TenantContextManager.executeWithTenant("1", () -> sysMenuService.findMenuByRoleId(SUPER_ROLE));
		List<MenuVO> inHome = TenantContextManager.executeWithTenant("-1", () -> sysMenuService.findMenuByRoleId(SUPER_ROLE));

		assertTrue(inTenant1.isEmpty());
		assertEquals(List.of("100", "101", "102"), ids(inHome), "-1 上下文必须拿到 -1 的行，而不是租户 1 缓存的空列表");
		assertTrue(menuCache.keys().containsAll(List.of("1:1_menu", "-1:1_menu")), "实际键：" + menuCache.keys());

		// 租户 1 管理员角色：两个租户下都有行，各查各的
		List<MenuVO> adminInHome = TenantContextManager.executeWithTenant("-1",
				() -> sysMenuService.findMenuByRoleId(TENANT1_ADMIN_ROLE));
		List<MenuVO> adminInTenant1 = TenantContextManager.executeWithTenant("1",
				() -> sysMenuService.findMenuByRoleId(TENANT1_ADMIN_ROLE));
		assertEquals(List.of("399"), ids(adminInHome));
		assertEquals(List.of("300", "301", "302"), ids(adminInTenant1),
				"租户 1 管理员拿到的必须是租户 1 的 3 行，不能是先被 -1 上下文填进缓存的那 1 行");

		// 再查一次：命中缓存（不再打库），结果不变
		assertEquals(List.of("300", "301", "302"), ids(TenantContextManager.executeWithTenant("1",
				() -> sysMenuService.findMenuByRoleId(TENANT1_ADMIN_ROLE))));
		verify(ctx.getBean(SysMenuMapper.class), times(2)).listMenusByRoleId(TENANT1_ADMIN_ROLE);
	}

	@Test
	@DisplayName("没有租户上下文（不过滤、查全部租户）时键里是 null，同样与各租户隔开")
	void nullTenantContextHasItsOwnKey() {
		TenantContextHolder.clear();
		List<MenuVO> all = sysMenuService.findMenuByRoleId(TENANT1_ADMIN_ROLE);

		assertEquals(4, all.size());
		assertTrue(menuCache.keys().contains("null:" + TENANT1_ADMIN_ROLE + "_menu"), "实际键：" + menuCache.keys());
	}

	// ---------------------------------------------------------------- /upms/menu

	private SysMenuController controller() {
		MenuSnapshotService snapshot = mock(MenuSnapshotService.class);
		when(snapshot.enabled()).thenReturn(false);
		return new SysMenuController(sysMenuService, snapshot);
	}

	private static void login(String tenantId, String type, String... roleIds) {
		List<String> authorities = new ArrayList<>();
		for (String roleId : roleIds) {
			authorities.add("ROLE_" + roleId);
		}
		BaseUser user = new BaseUser("u-" + tenantId, "1", type, tenantId, "user-" + tenantId,
				"", List.of(), true, true, true, true, AuthorityUtils.createAuthorityList(authorities));
		SecurityContextHolder.getContext()
				.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
	}

	@SuppressWarnings("unchecked")
	private static List<String> rootIds(R result) {
		return ((List<MenuTree>) result.getData()).stream().map(MenuTree::getId).collect(Collectors.toList());
	}

	/** 改前 getUserMenu 的方法体（不切回用户自己的租户），用于对照。 */
	private static List<MenuTree> legacyUserMenu(SysMenuService service, List<String> roleIds) {
		Set<MenuVO> all = new HashSet<>();
		roleIds.forEach(roleId -> all.addAll(service.findMenuByRoleId(roleId)));
		List<MenuTree> menuTreeList = all.stream()
				.filter(menuVo -> CommonConstants.MENU.equals(menuVo.getType()))
				.map(MenuTree::new)
				.sorted(Comparator.comparingInt(MenuTree::getSort))
				.collect(Collectors.toList());
		return TreeUtil.buildOptimized(menuTreeList, CommonConstants.PARENT_ID);
	}

	@Test
	@DisplayName("超管带 switch-tenant-id: 1 取 /upms/menu：改前（缓存冷）是空树，改后按超管自己的租户 -1 算，与不带头时一致")
	void superAdminMenuUnderSwitchedTenantIsComputedInHomeTenant() {
		login("-1", "-1", SUPER_ROLE, SUPER_ROLE_2);

		// 过滤器对超管 + switch-tenant-id: 1 做的事：上下文换成 1
		TenantContextHolder.setTenantId("1");
		List<MenuTree> before = legacyUserMenuVia(legacy::findMenuByRoleId, List.of(SUPER_ROLE, SUPER_ROLE_2));
		assertTrue(before.isEmpty(), "对照组：改前在切换后的租户里按超管角色查菜单，缓存冷时是空树");

		R withSwitch = controller().getUserMenu();
		assertEquals("1", TenantContextHolder.getTenantId(), "执行完要把上下文还原成切换后的租户，不影响同一请求的后续逻辑");

		TenantContextHolder.setTenantId("-1");
		R withoutSwitch = controller().getUserMenu();

		assertEquals(List.of("100", "200"), rootIds(withSwitch));
		assertEquals(rootIds(withoutSwitch), rootIds(withSwitch), "带不带 switch-tenant-id，超管自己的菜单都一样");
	}

	@Test
	@DisplayName("租户管理员：上下文本来就是自己的租户，/upms/menu 与改前算法一致")
	void tenantAdminMenuIsUnchanged() {
		login("1", "1", TENANT1_ADMIN_ROLE);
		TenantContextHolder.setTenantId("1");

		List<String> expected = legacyUserMenu(sysMenuService, List.of(TENANT1_ADMIN_ROLE)).stream()
				.map(MenuTree::getId).collect(Collectors.toList());

		assertEquals(List.of("300"), expected);
		assertEquals(expected, rootIds(controller().getUserMenu()));
	}

	/** 让 {@code legacy::findMenuByRoleId} 能当作 SysMenuService 用。 */
	private static List<MenuTree> legacyUserMenuVia(RoleMenuLookup lookup, List<String> roleIds) {
		SysMenuService service = mock(SysMenuService.class);
		when(service.findMenuByRoleId(anyString())).thenAnswer(inv -> lookup.find(inv.getArgument(0)));
		return legacyUserMenu(service, roleIds);
	}

	@FunctionalInterface
	interface RoleMenuLookup {
		List<MenuVO> find(String roleId);
	}
}
