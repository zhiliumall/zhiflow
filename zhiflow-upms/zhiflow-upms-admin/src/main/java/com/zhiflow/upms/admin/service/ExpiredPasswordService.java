package com.zhiflow.upms.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.data.tenant.TenantContextManager;
import com.zhiflow.common.security.login.LoginFailureLock;
import com.zhiflow.upms.admin.mapper.SysUserMapper;
import com.zhiflow.upms.common.dto.ExpiredPasswordChangeDTO;
import com.zhiflow.upms.common.entity.SysUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 「下次登录前必须修改密码」的账号改密（未登录调用，凭原口令）。
 *
 * <p>与密码登录同一套防爆破：共用登录失败锁定的计数与锁（{@link LoginFailureLock}），原口令错一次记一次失败；
 * 账号不存在与口令错误返回同一句话，不给枚举账号留差异。只接受带改密标记的账号，其余账号登录后在个人中心改。
 */
@Slf4j
@Service
public class ExpiredPasswordService {

	static final String BAD_CREDENTIALS = "账号或原口令错误";

	private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

	private final SysUserMapper sysUserMapper;

	private final CacheManager cacheManager;

	private final ObjectProvider<LoginFailureLock> loginFailureLock;

	public ExpiredPasswordService(SysUserMapper sysUserMapper, CacheManager cacheManager,
			ObjectProvider<LoginFailureLock> loginFailureLock) {
		this.sysUserMapper = sysUserMapper;
		this.cacheManager = cacheManager;
		this.loginFailureLock = loginFailureLock;
	}

	/**
	 * @return null = 成功；否则是给前端看的失败原因
	 */
	public String change(ExpiredPasswordChangeDTO dto) {
		LoginFailureLock lock = loginFailureLock.getIfAvailable();
		if (lock != null) {
			long locked = lock.lockedSeconds(dto.getUsername());
			if (locked > 0) {
				return "密码错误次数过多，账号已锁定，请 " + ((locked + 59) / 60) + " 分钟后再试";
			}
		}
		SysUser query = new SysUser();
		query.setUsername(dto.getUsername());
		SysUser user = sysUserMapper.getByNoTenant(query);
		if (user == null || user.getPassword() == null || user.getPassword().isEmpty()
				|| !ENCODER.matches(dto.getPassword(), user.getPassword())) {
			if (lock != null && lock.recordFailure(dto.getUsername())) {
				return "密码错误次数过多，账号已锁定 " + lock.lockMinutes() + " 分钟";
			}
			return BAD_CREDENTIALS;
		}
		if (!CommonConstants.YES.equals(user.getPasswordChangeRequired())) {
			return "该账号无需在这里修改密码，请登录后在个人中心修改";
		}
		if (dto.getPassword().equals(dto.getNewPassword())) {
			return "新密码不能与原口令相同";
		}
		int updated = TenantContextManager.executeWithTenant(user.getTenantId(), () -> sysUserMapper.update(null,
				Wrappers.<SysUser>lambdaUpdate()
					.set(SysUser::getPassword, ENCODER.encode(dto.getNewPassword()))
					.set(SysUser::getPasswordChangeRequired, CommonConstants.STATUS_NORMAL)
					.eq(SysUser::getId, user.getId())
					.eq(SysUser::getPasswordChangeRequired, CommonConstants.YES)));
		if (updated != 1) {
			// 并发：另一次请求刚改完
			return "密码已修改，请用新密码登录";
		}
		if (lock != null) {
			lock.recordSuccess(dto.getUsername());
		}
		Cache cache = cacheManager.getCache(CacheConstants.USER_CACHE);
		if (cache != null) {
			cache.evict(user.getUsername());
		}
		log.info("后台账号 {} 已完成首登 / 重置后改密", user.getUsername());
		return null;
	}
}
