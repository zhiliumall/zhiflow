package com.zhiflow.auth.core;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.WebUtils;
import com.zhiflow.common.security.login.LoginFailureLock;
import com.zhiflow.common.security.service.BaseUserDetailsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import org.springframework.core.Ordered;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.AbstractUserDetailsAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.authentication.www.BasicAuthenticationConverter;
import org.springframework.util.Assert;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * @author 辰
 * @date 2023年10月26日18:27:57
 */
public class BaseDaoAuthenticationProvider extends AbstractUserDetailsAuthenticationProvider {

	/**
	 * The plaintext password used to perform PasswordEncoder#matches(CharSequence,
	 * String)} on when the user is not found to avoid SEC-2056.
	 */
	private static final String USER_NOT_FOUND_PASSWORD = "userNotFoundPassword";

	private final static BasicAuthenticationConverter basicConvert = new BasicAuthenticationConverter();

	private PasswordEncoder passwordEncoder;

	/**
	 * The password used to perform {@link PasswordEncoder#matches(CharSequence, String)}
	 * on when the user is not found to avoid SEC-2056. This is necessary, because some
	 * {@link PasswordEncoder} implementations will short circuit if the password is not
	 * in a valid format.
	 */
	private volatile String userNotFoundEncodedPassword;

	private UserDetailsService userDetailsService;

	private UserDetailsPasswordService userDetailsPasswordService;

	public BaseDaoAuthenticationProvider() {
		setMessageSource(SpringUtil.getBean("securityMessageSource"));
		setPasswordEncoder(PasswordEncoderFactories.createDelegatingPasswordEncoder());
	}

	/** 失败锁定，首次用到时从容器取 Redis 与配置；取不到（如没有 Redis）就不锁。 */
	private volatile LoginFailureLock loginFailureLock;

	private volatile boolean loginFailureLockResolved;

	/**
	 * 先看账号是否已锁（锁定期间密码对了也不放行），再走父类认证；密码错计数，达到上限上锁。
	 * 用户名不存在在父类里同样抛 {@link BadCredentialsException}，计数方式一致。
	 */
	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		String username = authentication.getName();
		LoginFailureLock lock = loginFailureLock();
		if (lock != null) {
			long locked = lock.lockedSeconds(username);
			if (locked > 0) {
				throw new LockedException("密码错误次数过多，账号已锁定，请 " + ((locked + 59) / 60) + " 分钟后再试");
			}
		}
		try {
			Authentication result = super.authenticate(authentication);
			if (lock != null) {
				lock.recordSuccess(username);
			}
			return result;
		}
		catch (BadCredentialsException e) {
			if (lock != null && lock.recordFailure(username)) {
				throw new LockedException("密码错误次数过多，账号已锁定 " + lock.lockMinutes() + " 分钟");
			}
			throw e;
		}
	}

	private LoginFailureLock loginFailureLock() {
		if (!loginFailureLockResolved) {
			try {
				// 本类在 Security 装配阶段被 new 出来，拿不到注入，首次用到时再从容器取；没开锁定（或没有 Redis）时没有这个 bean
				loginFailureLock = SpringUtil.getBeanFactory().getBeanProvider(LoginFailureLock.class).getIfAvailable();
			}
			catch (RuntimeException e) {
				this.logger.warn("后台登录失败锁定未启用：" + e);
			}
			loginFailureLockResolved = true;
		}
		return loginFailureLock;
	}

	@Override
	@SuppressWarnings("deprecation")
	protected void additionalAuthenticationChecks(UserDetails userDetails,
			UsernamePasswordAuthenticationToken authentication) throws AuthenticationException {

		if (authentication.getCredentials() == null) {
			this.logger.debug("Failed to authenticate since no credentials provided");
			throw new BadCredentialsException(this.messages
				.getMessage("AbstractUserDetailsAuthenticationProvider.badCredentials", "Bad credentials"));
		}
		String presentedPassword = authentication.getCredentials().toString();
		if (!this.passwordEncoder.matches(presentedPassword, userDetails.getPassword())) {
			this.logger.debug("Failed to authenticate since password does not match stored value");
			throw new BadCredentialsException(this.messages
				.getMessage("AbstractUserDetailsAuthenticationProvider.badCredentials", "Bad credentials"));
		}
	}

	@SneakyThrows
	@Override
	protected final UserDetails retrieveUser(String username, UsernamePasswordAuthenticationToken authentication) {
		prepareTimingAttackProtection();
		HttpServletRequest request = WebUtils.getRequest()
			.orElseThrow(
					(Supplier<Throwable>) () -> new InternalAuthenticationServiceException("web request is empty"));

		String grantType = WebUtils.getRequest().get().getParameter(OAuth2ParameterNames.GRANT_TYPE);
		String clientId = WebUtils.getRequest().get().getParameter(OAuth2ParameterNames.CLIENT_ID);

		if (StrUtil.isBlank(clientId)) {
			clientId = basicConvert.convert(request).getName();
		}

		Map<String, BaseUserDetailsService> userDetailsServiceMap = SpringUtil
			.getBeansOfType(BaseUserDetailsService.class);

		String finalClientId = clientId;
		Optional<BaseUserDetailsService> optional = userDetailsServiceMap.values()
			.stream()
			.filter(service -> service.support(finalClientId, grantType))
			.max(Comparator.comparingInt(Ordered::getOrder));

		if (optional.isEmpty()) {
			throw new InternalAuthenticationServiceException("UserDetailsService error , not register");
		}

		try {
			UserDetails loadedUser = null;
			// 开源版只保留账号密码授权（短信、第三方授权方式已删，S-15 / G-13）
			if (grantType.equals(SecurityConstants.PASSWORD)) {
				loadedUser = optional.get().loadUserByUsername(username);
			}
			if (loadedUser == null) {
				throw new InternalAuthenticationServiceException(
						"UserDetailsService returned null, which is an interface contract violation");
			}
			return loadedUser;
		}
		catch (UsernameNotFoundException ex) {
			mitigateAgainstTimingAttack(authentication);
			throw ex;
		}
		catch (InternalAuthenticationServiceException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new InternalAuthenticationServiceException(ex.getMessage(), ex);
		}
	}

	@Override
	protected Authentication createSuccessAuthentication(Object principal, Authentication authentication,
			UserDetails user) {
		boolean upgradeEncoding = this.userDetailsPasswordService != null
				&& this.passwordEncoder.upgradeEncoding(user.getPassword());
		if (upgradeEncoding) {
			String presentedPassword = authentication.getCredentials().toString();
			String newPassword = this.passwordEncoder.encode(presentedPassword);
			user = this.userDetailsPasswordService.updatePassword(user, newPassword);
		}
		return super.createSuccessAuthentication(principal, authentication, user);
	}

	private void prepareTimingAttackProtection() {
		if (this.userNotFoundEncodedPassword == null) {
			this.userNotFoundEncodedPassword = this.passwordEncoder.encode(USER_NOT_FOUND_PASSWORD);
		}
	}

	private void mitigateAgainstTimingAttack(UsernamePasswordAuthenticationToken authentication) {
		if (authentication.getCredentials() != null) {
			String presentedPassword = authentication.getCredentials().toString();
			this.passwordEncoder.matches(presentedPassword, this.userNotFoundEncodedPassword);
		}
	}

	/**
	 * Sets the PasswordEncoder instance to be used to encode and validate passwords. If
	 * not set, the password will be compared using
	 * {@link PasswordEncoderFactories#createDelegatingPasswordEncoder()}
	 * @param passwordEncoder must be an instance of one of the {@code PasswordEncoder}
	 * types.
	 */
	public void setPasswordEncoder(PasswordEncoder passwordEncoder) {
		Assert.notNull(passwordEncoder, "passwordEncoder cannot be null");
		this.passwordEncoder = passwordEncoder;
		this.userNotFoundEncodedPassword = null;
	}

	protected PasswordEncoder getPasswordEncoder() {
		return this.passwordEncoder;
	}

	public void setUserDetailsService(UserDetailsService userDetailsService) {
		this.userDetailsService = userDetailsService;
	}

	protected UserDetailsService getUserDetailsService() {
		return this.userDetailsService;
	}

	public void setUserDetailsPasswordService(UserDetailsPasswordService userDetailsPasswordService) {
		this.userDetailsPasswordService = userDetailsPasswordService;
	}
}
