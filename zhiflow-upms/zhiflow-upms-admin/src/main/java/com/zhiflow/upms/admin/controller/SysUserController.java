package com.zhiflow.upms.admin.controller;

import com.zhiflow.upms.admin.projection.UpmsInsideProjections;

import com.zhiflow.upms.common.dto.SysUserQuery;

import com.zhiflow.upms.common.dto.SysUserInfo;

import com.zhiflow.upms.common.dto.AuthUserInfo;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.api.weixin.WxUserApi;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.upms.admin.mapper.SysUserMapper;
import com.zhiflow.upms.admin.service.*;
import com.zhiflow.upms.common.dto.ExpiredPasswordChangeDTO;
import com.zhiflow.upms.common.dto.UserDTO;
import com.zhiflow.upms.common.dto.UserInfo;
import com.zhiflow.upms.common.entity.SysRole;
import com.zhiflow.upms.common.entity.SysTenant;
import com.zhiflow.upms.common.entity.SysUser;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.lock.ratelimit.RateLimit;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.data.tenant.TenantContextManager;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.common.security.annotation.Inside;
import com.zhiflow.common.security.util.SecurityUtils;
import com.zhiflow.upms.common.entity.SysUserRole;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import java.util.List;

/**
 * @author
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/user")
@Tag(description = "user", name = "用户管理模块")
public class SysUserController {
	private final SysUserMapper sysUserMapper;
	private final SysUserService sysUserService;
	private final SysRoleService sysRoleService;
	private final SysUserRoleService sysUserRoleService;
	private final RedisTemplate redisTemplate;
	private final SysTenantService sysTenantService;
	private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();
	private final CacheManager cacheManager;

	private final WxUserApi wxUserApi;
	private final ExpiredPasswordService expiredPasswordService;

	/**
	 * 获取当前用户全部信息
	 *
	 * @return 用户信息
	 */
	@Operation(summary = "获取当前用户全部信息")
	@GetMapping(value = {"/info"})
	public R info() {
		BaseUser baseUser = SecurityUtils.getUser();
		String userName = baseUser.getUsername();
		SysUser sysUser = TenantContextManager.executeWithTenant(baseUser.getTenantId(), () ->
				sysUserService.getOne(Wrappers.<SysUser>query()
						.lambda().eq(SysUser::getUsername, userName)));
		if (sysUser == null) {
			return R.failed(null, "获取当前用户信息失败");
		}
		UserInfo userInfo = TenantContextManager.executeWithTenant(baseUser.getTenantId(), () ->
				sysUserService.findUserInfo(sysUser));
		return R.ok(userInfo);
	}

	/**
	 * 获取指定用户全部信息
	 *
	 * @return 用户信息
	 */
	@Operation(summary = "获取指定用户全部信息")
	@Inside
	@GetMapping("/info/{username}")
	public R<AuthUserInfo> info(@PathVariable String username) {
		SysUser sysUser = new SysUser();
		sysUser.setUsername(username);
		sysUser = sysUserService.getByNoTenant(sysUser);
		if (sysUser == null) {
			return R.failed(null, "用户不存在 %s".formatted(username));
		}
		final SysUser finalSysUser = sysUser;
		return TenantContextManager.executeWithTenant(sysUser.getTenantId(), () -> {
			this.setUserLockFlag(finalSysUser);
			UserInfo userInfo = sysUserService.findUserInfo(finalSysUser);
			return R.ok(UpmsInsideProjections.authUser(userInfo));
		});
	}

	/**
	 * 通过ID查询用户信息
	 *
	 * @param id ID
	 * @return 用户信息
	 */
	@Operation(summary = "通过ID查询用户信息")
	@GetMapping("/{id}")
	@PreAuthorize("@ato.hasAuthority('sys:user:get')")
	public R user(@PathVariable String id) {
		SysUser sysUser = sysUserService.getById(id);
		// 只在本接口出口去掉密码哈希；SysUser 实体全局序列化不动（请求体反序列化与缓存仍需要 password）
		if (sysUser != null) {
			sysUser.setPassword(null);
		}
		return R.ok(sysUser);
	}

	/**
	 * 删除用户信息
	 *
	 * @param id ID
	 * @return R
	 */
	@SysLog("删除用户信息")
	@DeleteMapping("/{id}")
	@PreAuthorize("@ato.hasAuthority('sys:user:del')")
	@Operation(summary = "删除用户")
	public R userDel(@PathVariable String id) {
		//拥有管理员角色的用户不让删除
		SysRole sysRole = sysRoleService.getOne(Wrappers.<SysRole>update().lambda()
				.eq(SysRole::getRoleCode,CommonConstants.ROLE_CODE_ADMIN));
		List<SysUserRole> listSysUserRole = sysUserRoleService.list(Wrappers.<SysUserRole>update().lambda()
				.eq(SysUserRole::getUserId,id).eq(SysUserRole::getRoleId,sysRole.getId()));
		if(listSysUserRole.size()>0){
			return R.failed("无法删除拥有管理员角色的用户");
		}
		SysUser sysUser = sysUserService.getById(id);
		return R.ok(sysUserService.deleteUserById(sysUser));
	}

	/**
	 * 添加用户
	 *
	 * @param userDto 用户信息
	 * @return ok/false
	 */
	@Operation(summary = "添加用户")
	@SysLog("添加用户")
	@PostMapping
	@PreAuthorize("@ato.hasAuthority('sys:user:add')")
	public R user(@RequestBody UserDTO userDto) {
		try{
			if(CommonConstants.USER_TYPE_S.equals(userDto.getType())){
				if(!StrUtil.equals(CommonConstants.USER_TYPE_S, SecurityUtils.getUser().getType())
						&& !StrUtil.equals(CommonConstants.SYSTEM_TENANT_ID, SecurityUtils.getUser().getTenantId())){
					return R.failed("非法操作！非系统管理员用户不能新增系统管理员类型的用户");
				}
			}
			return R.ok(sysUserService.saveUser(userDto));
		}catch (DuplicateKeyException e){
			return judeParm(e);
		}
	}

	/**
	 * 更新用户信息
	 *
	 * @param userDto 用户信息
	 * @return R
	 */
	@Operation(summary = "更新用户信息")
	@SysLog("更新用户信息")
	@PutMapping
	@PreAuthorize("@ato.hasAuthority('sys:user:edit')")
	public R updateUser(@Valid @RequestBody UserDTO userDto) {
		try{
			if(CommonConstants.USER_TYPE_S.equals(userDto.getType())){
				if(!StrUtil.equals(CommonConstants.USER_TYPE_S, SecurityUtils.getUser().getType())
						&& !StrUtil.equals(CommonConstants.SYSTEM_TENANT_ID, SecurityUtils.getUser().getTenantId())){
					return R.failed("非法操作！非系统管理员用户不能操作系统管理员类型的用户");
				}
			}
			//查询出管理员角色，判断管理员角色是否至少有1个用户
			SysRole sysRole = sysRoleService.getOne(Wrappers.<SysRole>update().lambda()
					.eq(SysRole::getRoleCode,CommonConstants.ROLE_CODE_ADMIN));
			if(!CollUtil.contains(userDto.getRoleIds(),sysRole.getId())){
				List<SysUserRole> listSysUserRole = sysUserRoleService.list(Wrappers.<SysUserRole>update().lambda()
						.eq(SysUserRole::getRoleId,sysRole.getId()));
				if(listSysUserRole.size()<=1){//只有一条记录，判断是否当前用户拥有
					listSysUserRole = sysUserRoleService.list(Wrappers.<SysUserRole>update().lambda()
							.eq(SysUserRole::getRoleId,sysRole.getId())
							.eq(SysUserRole::getUserId,userDto.getId()));
					if(listSysUserRole.size()>0){
						return R.failed("至少需要一个用户拥有管理员角色");
					}
				}
			}
			return R.ok(sysUserService.updateUser(userDto));
		}catch (DuplicateKeyException e){
			return judeParm(e);
		}
	}

	/**
	 * 修改用户密码
	 * @param userDto
	 * @return
	 */
	@Operation(summary = "修改用户密码")
	@SysLog("修改用户密码")
	@PutMapping("/password")
	@PreAuthorize("@ato.hasAuthority('sys:user:password')")
	public R editPassword(@Valid @RequestBody UserDTO userDto) {
		SysUser sysUser = sysUserService.getById(userDto.getId());
		if(sysUser == null){
			return R.failed("用户不存在");
		}
		sysUser.setPassword(ENCODER.encode(userDto.getPassword()));
		// 管理员知道这个口令：本人下次登录前必须改掉
		sysUser.setPasswordChangeRequired(CommonConstants.YES);
		sysUserService.updateById(sysUser);
		//更新緩存
		cacheManager.getCache(CacheConstants.USER_CACHE).evict(sysUser.getUsername());
		return R.ok();
	}

	/**
	 * 首次登录 / 后台重置之后修改密码。账号带「下次登录前必须修改密码」标记时登录会被拒绝（凭证过期），
	 * 前端引导到这里；免登录，凭原口令，限流并与密码登录共用失败锁定。
	 */
	@Operation(summary = "修改过期密码（免登录）")
	@SysLog("修改过期密码")
	@RateLimit(name = "password-expired", permits = 10, periodSeconds = 60, message = "操作太频繁，请稍后再试")
	@PutMapping("/password/expired")
	public R changeExpiredPassword(@Valid @RequestBody ExpiredPasswordChangeDTO dto) {
		String error = expiredPasswordService.change(dto);
		return error == null ? R.ok() : R.failed(error);
	}

	/**
	 * 分页查询用户
	 *
	 * @param page    参数集
	 * @param userDTO 查询参数列表
	 * @return 用户集合
	 */
	@Operation(summary = "分页查询")
	@GetMapping("/page")
	@PreAuthorize("@ato.hasAuthority('sys:user:index')")
	public R getUserPage(Page page, UserDTO userDTO) {
		return R.ok(sysUserService.getUsersPage(page, userDTO));
	}

	/**
	 * 分页查询客服用户
	 *
	 * @return 用户集合
	 */
	@Operation(summary = "分页查询")
	@PostMapping("/inside/list")
	@Inside
	public R<List<SysUserInfo>> getInsideUserList(@RequestBody SysUserQuery sysUser) {
		return R.ok(UpmsInsideProjections.users(sysUserMapper.listSysUser(UpmsInsideProjections.userQuery(sysUser))));
	}

	/**
	 * 数量查询
	 * @param sysUser
	 * @return
	 */
	@Operation(summary = "数量查询")
	@GetMapping("/count")
	public R getCount(SysUser sysUser) {
		return R.ok(sysUserMapper.selectCountNoTenant(sysUser));
	}

	/**
	 * 修改个人信息
	 *
	 * @param userDto userDto
	 * @return ok/false
	 */
	@Operation(summary = "修改个人信息")
	@SysLog("修改个人信息")
	@PutMapping("/edit")
	public R updateUserInfo(@Valid @RequestBody UserDTO userDto) {
		userDto.setId(SecurityUtils.getUser().getId());
		userDto.setUsername(SecurityUtils.getUser().getUsername());
		return R.ok(sysUserService.updateUserInfo(userDto));
	}

	/**
	 * 绑定/解绑手机号
	 *
	 * @param userDto userDto
	 * @return
	 */

	// [B5] listAncestorUsers 已删除 —— 它只被 UserApi#ancestorUsers 这条**死边**打到
	// （@Inside 端点，外部打不进来；契约那侧全仓零业务调用方，对照样本见 UserApi 的注释）。
	// 契约方法删了之后本端点无人可达，留着只会让下一轮扫描再数一遍 ENTITY 债。

	/**
	 *
	 * @param e
	 * @return
	 */
	R judeParm(DuplicateKeyException e){
		if(e.getMessage().contains("uk_username")){
			return R.failed("用户名已被占用");
		}else if(e.getMessage().contains("uk_email")){
			return R.failed("邮箱已被占用");
		}else{
			return R.failed(e.getMessage());
		}
	}

	void setUserLockFlag(SysUser sysUser){
		if(CommonConstants.STATUS_NORMAL.equals(sysUser.getLockFlag())){
			//查询所属租户状态是否正常，否则禁止登录
			SysTenant sysTenant = sysTenantService.getById(sysUser.getTenantId());
			if(sysTenant == null){
				// 账号还在、所属租户已被删：按锁定处理（禁止登录）。原先直接 NPE，手机号登录回 500，
				// 2026-09-15 12:53 / 21:11 经 gRPC getUserInfoByPhone 各撞一次。
				log.warn("用户所属租户不存在，按锁定处理 userId={} tenantId={}", sysUser.getId(), sysUser.getTenantId());
				sysUser.setLockFlag(CommonConstants.STATUS_LOCK);
				return;
			}
			if(CommonConstants.STATUS_LOCK.equals(sysTenant.getStatus())){
				sysUser.setLockFlag(CommonConstants.STATUS_LOCK);
			}
		}
	}
}
