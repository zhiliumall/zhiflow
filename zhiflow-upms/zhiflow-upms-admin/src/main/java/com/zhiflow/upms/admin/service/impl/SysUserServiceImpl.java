package com.zhiflow.upms.admin.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.enums.LoginTypeEnum;
import com.zhiflow.common.security.util.SecurityUtils;
import com.zhiflow.upms.admin.service.*;
import com.zhiflow.upms.admin.mapper.SysUserMapper;
import com.zhiflow.upms.admin.mapper.SysRoleMapper;
import com.zhiflow.upms.admin.mapper.SysUserTenantMapper;
import com.zhiflow.upms.common.dto.UserDTO;
import com.zhiflow.upms.common.dto.UserInfo;
import com.zhiflow.upms.common.entity.*;
import com.zhiflow.upms.common.vo.MenuVO;
import com.zhiflow.upms.common.vo.UserVO;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.data.cache.AfterCommitCacheEviction;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author
 */
@Slf4j
@Service
@AllArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {
	private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();
	private final SysMenuService sysMenuService;
	private final SysRoleService sysRoleService;
	private final SysUserRoleService sysUserRoleService;
	private final SysRoleMenuService sysRoleMenuService;
	private final CacheManager cacheManager;
	private final SysUserTenantService sysUserTenantService;
	private final SysRoleMapper sysRoleMapper;
	private final SysUserTenantMapper sysUserTenantMapper;
	private final MenuSnapshotService menuSnapshotService;

	/**
	 * 保存用户信息
	 *
	 * @param userDto DTO 对象
	 * @return ok/fail
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean saveUser(UserDTO userDto) {
		//参数校验：避免 password/roleIds 为空时 encode()/stream() 触发 NPE→500，改为干净的参数异常
		if (StrUtil.isBlank(userDto.getPassword())) {
			throw new IllegalArgumentException("密码不能为空");
		}
		if (CollUtil.isEmpty(userDto.getRoleIds())) {
			throw new IllegalArgumentException("角色不能为空");
		}
		SysUser sysUser = new SysUser();
		BeanUtils.copyProperties(userDto, sysUser);
		sysUser.setDelFlag(CommonConstants.STATUS_NORMAL);
		sysUser.setPassword(ENCODER.encode(userDto.getPassword()));
		baseMapper.insert(sysUser);
		//新增用户角色关联
		List<SysUserRole> userRoleList = userDto.getRoleIds()
				.stream().map(roleId -> {
					SysUserRole userRole = new SysUserRole();
					userRole.setUserId(sysUser.getId());
					userRole.setRoleId(roleId);
					return userRole;
				}).collect(Collectors.toList());
		sysUserRoleService.saveBatch(userRoleList);
		//平台系统管理员关联租户
		if(StrUtil.equals(CommonConstants.USER_TYPE_S, sysUser.getType())){
			//仅系统管理员分支才要求 tenantIds，避免 stream() 空指针
			if (CollUtil.isEmpty(userDto.getTenantIds())) {
				throw new IllegalArgumentException("租户不能为空");
			}
			List<SysUserTenant> sysUserTenantList = userDto.getTenantIds()
					.stream().map(tenantId -> {
						SysUserTenant sysUserTenant = new SysUserTenant();
						sysUserTenant.setUserId(sysUser.getId());
						sysUserTenant.setTenantId(tenantId);
						return sysUserTenant;
					}).collect(Collectors.toList());
			sysUserTenantService.saveBatch(sysUserTenantList);
		}
		userDto.setId(sysUser.getId());
		return Boolean.TRUE;
	}

	/**
	 * 通过查用户的全部信息
	 *
	 * @param sysUser 用户
	 * @return
	 */
	@Override
	public UserInfo findUserInfo(SysUser sysUser) {
		UserInfo userInfo = new UserInfo();
		userInfo.setSysUser(sysUser);
		//设置角色列表  （ID）
		List<String> roleIds = sysRoleService.findRoleIdsByUserId(sysUser.getId());
		userInfo.setRoles(ArrayUtil.toArray(roleIds, String.class));

		// 开关打开时取 (租户, 角色列表) 的权限快照；与下面的原算法对同一输入逐字节相同（见 MenuSnapshotService）。
		// 租户取当前上下文：各调用方都已包在 executeWithTenant(用户自己的租户) 里，下面原算法查基础缓存用的也是它
		if (menuSnapshotService.enabled()) {
			userInfo.setPermissions(menuSnapshotService.permissions(TenantContextHolder.getTenantId(), roleIds)
					.toArray(new String[0]));
			return userInfo;
		}

		//设置权限列表（menu.permission）
		Set<String> permissions = new HashSet<>();
		roleIds.forEach(roleId -> {
			List<String> permissionList = sysMenuService.findMenuByRoleId(roleId)
					.stream()
					.filter(menuVo -> StrUtil.isNotEmpty(menuVo.getPermission()))
					.map(MenuVO::getPermission)
					.collect(Collectors.toList());
			permissions.addAll(permissionList);
		});
		userInfo.setPermissions(ArrayUtil.toArray(permissions, String.class));
		return userInfo;
	}

	/**
	 * 分页查询用户信息（含有角色信息）
	 *
	 * @param page    分页对象
	 * @param userDTO 参数列表
	 * @return
	 */
	@Override
	public IPage getUsersPage(Page page, UserDTO userDTO) {
		// 主查（含 DataScope 数据权限注入，保持 mapperId 与 SQL 不动）
		IPage userPage = baseMapper.getUserVosPage(page, userDTO);
		List<UserVO> records = userPage.getRecords();
		if (records != null && !records.isEmpty()) {
			// C-09：两段式装配 roleIds/tenantIds，把原 1+2N 条 N+1 降为 3 条 SQL
			List<String> userIds = records.stream()
					.map(UserVO::getId)
					.collect(Collectors.toList());
			Map<String, List<String>> roleMap = groupByUser(
					sysRoleMapper.listUserRolesByUserIds(userIds), "roleId");
			Map<String, List<String>> tenantMap = groupByUser(
					sysUserTenantMapper.listUserTenantsByUserIds(userIds), "tenantId");
			// roleList（真实角色名）批量装配：一条 SQL 查全部用户的角色明细后内存分组，不引入 N+1
			Map<String, List<SysRole>> roleListMap = groupRolesByUser(
					sysRoleMapper.listUserRolesDetailByUserIds(userIds));
			records.forEach(user -> {
				user.setRoleIds(roleMap.getOrDefault(user.getId(), Collections.emptyList()));
				user.setTenantIds(tenantMap.getOrDefault(user.getId(), Collections.emptyList()));
				user.setRoleList(roleListMap.getOrDefault(user.getId(), Collections.emptyList()));
			});
		}
		return userPage;
	}

	/**
	 * 按 userId 归并批量查询结果为 userId -> valueList，保持行到达顺序（LinkedHashMap + 顺序追加）
	 */
	private Map<String, List<String>> groupByUser(List<Map<String, Object>> rows, String valueKey) {
		Map<String, List<String>> result = new LinkedHashMap<>();
		for (Map<String, Object> row : rows) {
			Object userId = row.get("userId");
			if (userId == null) {
				continue;
			}
			List<String> list = result.computeIfAbsent(String.valueOf(userId), k -> new ArrayList<>());
			Object value = row.get(valueKey);
			if (value != null) {
				list.add(String.valueOf(value));
			}
		}
		return result;
	}

	/**
	 * 按 userId 归并角色明细行为 userId -> List&lt;SysRole&gt;，保持行到达顺序（镜像 groupByUser 装配方式）
	 */
	private Map<String, List<SysRole>> groupRolesByUser(List<Map<String, Object>> rows) {
		Map<String, List<SysRole>> result = new LinkedHashMap<>();
		for (Map<String, Object> row : rows) {
			Object userId = row.get("userId");
			Object roleId = row.get("roleId");
			if (userId == null || roleId == null) {
				continue;
			}
			SysRole role = new SysRole();
			role.setId(String.valueOf(roleId));
			Object roleName = row.get("roleName");
			if (roleName != null) {
				role.setRoleName(String.valueOf(roleName));
			}
			Object roleCode = row.get("roleCode");
			if (roleCode != null) {
				role.setRoleCode(String.valueOf(roleCode));
			}
			Object roleDesc = row.get("roleDesc");
			if (roleDesc != null) {
				role.setRoleDesc(String.valueOf(roleDesc));
			}
			result.computeIfAbsent(String.valueOf(userId), k -> new ArrayList<>()).add(role);
		}
		return result;
	}

	/**
	 * 员工数量统计（在职/子账号）。
	 * 在职(active)=lock_flag='0'（0 正常/未锁定）；子账号(total)=作用域内员工总数。
	 * 作用域与 /user/page(getUserVosPage) 完全一致(type/shopId/tenant)，保证统计与列表口径吻合。
	 * 说明：sys_user 无 owner/sub 区分字段，故「子账号」直接取作用域内员工总数。
	 */
	@Override
	public Map<String, Object> getStaffCount(UserDTO userDTO) {
		return baseMapper.getStaffCount(userDTO);
	}

	/**
	 * 通过ID查询用户信息
	 *
	 * @param id 用户ID
	 * @return 用户信息
	 */
	@Override
	public UserVO selectUserVoById(String id) {
		return baseMapper.getUserVoById(id);
	}

	/**
	 * 删除用户
	 *
	 * @param sysUser 用户
	 * @return Boolean
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean deleteUserById(SysUser sysUser) {
		sysUserRoleService.deleteByUserId(sysUser.getId());
		sysUserTenantService.remove(Wrappers.<SysUserTenant>lambdaQuery()
				.eq(SysUserTenant::getUserId,sysUser.getId()));
		this.removeById(sysUser.getId());
		// 原 @CacheEvict(key = "#sysUser.username") 与 @Transactional 谁先谁后没有保证；改为提交后再清
		AfterCommitCacheEviction.evict(cacheManager, CacheConstants.USER_CACHE, sysUser.getUsername());
		return Boolean.TRUE;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean updateUserInfo(UserDTO userDto) {
		UserVO userVO = baseMapper.getUserVoById(userDto.getId());
		SysUser sysUser = new SysUser();
		if (StrUtil.isNotBlank(userDto.getPassword())
				&& StrUtil.isNotBlank(userDto.getNewpassword1())) {//修改密码
			if (ENCODER.matches(userDto.getPassword(), userVO.getPassword())) {
				sysUser.setPassword(ENCODER.encode(userDto.getNewpassword1()));
			} else {
				log.warn("原密码错误，修改密码失败:{}", userDto.getUsername());
				throw new RuntimeException("原密码错误，修改失败");
			}
		}
		sysUser.setId(userVO.getId());
		sysUser.setAvatar(userDto.getAvatar());
		sysUser.setEmail(userVO.getEmail());
		sysUser.setNickName(userDto.getNickName());
		baseMapper.updateById(sysUser);
		// 原 @CacheEvict(key = "#userDto.username")：改为提交后再清，否则提交前进来的请求会把旧密码哈希写回用户缓存
		AfterCommitCacheEviction.evict(cacheManager, CacheConstants.USER_CACHE, userDto.getUsername());
		return Boolean.TRUE;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean updateUser(UserDTO userDto) {
		SysUser sysUser = new SysUser();
		BeanUtils.copyProperties(userDto, sysUser);
		sysUser.setUpdateTime(LocalDateTime.now());
		sysUser.setPassword(null);
		this.updateById(sysUser);

		sysUserRoleService.remove(Wrappers.<SysUserRole>update().lambda()
				.eq(SysUserRole::getUserId, userDto.getId()));
		userDto.getRoleIds().forEach(roleId -> {
			SysUserRole userRole = new SysUserRole();
			userRole.setUserId(sysUser.getId());
			userRole.setRoleId(roleId);
			sysUserRoleService.save(userRole);
		});

		//平台系统管理员关联租户
		if(StrUtil.equals(CommonConstants.USER_TYPE_S, sysUser.getType())){
			sysUserTenantService.remove(Wrappers.<SysUserTenant>update().lambda()
					.eq(SysUserTenant::getUserId, userDto.getId()));
			userDto.getTenantIds().forEach(tenantId -> {
				SysUserTenant sysUserTenant = new SysUserTenant();
				sysUserTenant.setUserId(sysUser.getId());
				sysUserTenant.setTenantId(tenantId);
				sysUserTenantService.save(sysUserTenant);
			});
		}

		// 原 @CacheEvict(key = "#userDto.username")：改为提交后再清。改角色时若在提交前清，
		// 期间进来的请求会按旧角色重建用户缓存（旧权限）并保留 2 小时
		AfterCommitCacheEviction.evict(cacheManager, CacheConstants.USER_CACHE, userDto.getUsername());
		return Boolean.TRUE;
	}

	/**
	 * 跨租户按唯一标识查用户。
	 *
	 * <p><b>入参全空必须直接返回 null，不能放给 SQL。</b>{@code SysUserMapper.getByNoTenant}
	 * 的 {@code <where>} 是逐字段动态拼的，一个条件都没有时退化成
	 * {@code SELECT ... FROM sys_user}（**全表、且跨全部租户**），而它是 {@code selectOne}：
	 * <ul>
	 *   <li>库里多于一行 → {@code TooManyResultsException} → 500 +「服务异常」+ 一条
	 *       {@code sys_error_log}（2026-08-15 实测 27 行）；</li>
	 *   <li><b>库里恰好一行 → 静默返回那个用户的完整记录</b>，而 {@code sysUserMap}
	 *       映射了 {@code password} 与 {@code salt}。这一支才是真正危险的：它不报错。</li>
	 * </ul>
	 *
	 * <p>守卫放在 Service 而不是 Controller：调用点（{@code info/{username}}、改过期密码、按手机号查）
	 * 都可能传进空字段，在这里挡一次，各处一起受益。
	 */
	@Override
	public SysUser getByNoTenant(SysUser sysUser) {
		if (sysUser == null || StrUtil.isAllBlank(sysUser.getUsername(), sysUser.getEmail(), sysUser.getPhone())) {
			log.warn("getByNoTenant 收到全空查询条件，按「查不到」处理，不下发无条件全表查询");
			return null;
		}
		return baseMapper.getByNoTenant(sysUser);
	}

	@Override
	public void bindPhone(UserDTO userDto) {
		SysUser sysUser = baseMapper.selectById(userDto.getId());
		LambdaUpdateWrapper<SysUser> userUpdateWrapper = new UpdateWrapper<SysUser>().lambda();
		userUpdateWrapper.eq(SysUser::getId,userDto.getId());
		if(CommonConstants.PHONE_CODE_2.equals(userDto.getDoType())){//绑定手机
			if(StrUtil.isNotBlank(sysUser.getPhone())){
				throw new RuntimeException("该账号已有手机号绑定，请先解绑");
			}
			userUpdateWrapper.set(SysUser::getPhone, userDto.getPhone());
		}else if(CommonConstants.PHONE_CODE_3.equals(userDto.getDoType())){//解绑
			userUpdateWrapper.set(SysUser::getPhone, null);
		}
		this.update(userUpdateWrapper);
		//更新緩存
		cacheManager.getCache(CacheConstants.USER_CACHE).evict(sysUser.getUsername());
	}

}
