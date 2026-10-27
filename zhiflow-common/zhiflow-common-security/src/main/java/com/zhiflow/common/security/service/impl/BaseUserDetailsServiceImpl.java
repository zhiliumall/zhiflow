package com.zhiflow.common.security.service.impl;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.common.security.service.BaseUserDetailsService;
import com.zhiflow.upms.common.dto.AuthUserIdentity;
import com.zhiflow.upms.common.dto.AuthUserInfo;
import com.zhiflow.api.upms.UserApi;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 用户详细信息
 *
 * @author 辰
 * @date 2023年10月26日17:35:32
 */
@Slf4j
@Primary
@Service
@AllArgsConstructor
public class BaseUserDetailsServiceImpl implements BaseUserDetailsService {
	private final UserApi userApi;
	@Autowired
	private  CacheManager cacheManager;

	/**
	 * 用户密码登录
	 *
	 * @param username 用户名
	 * @return
	 * @throws UsernameNotFoundException
	 */
	@Override
	@SneakyThrows
	public UserDetails loadUserByUsername(String username) {
		//查询缓存中是否有此用户信息，有则直接返回
		Cache cache = cacheManager.getCache(CacheConstants.USER_CACHE);
		// 只 get 一次（压测优化 A1）：原先判空、取值各 get 一次 = 每个后台请求两次约 99 KB 的 GET + 两次 Jackson 反序列化；
		// 两次之间键若过期 / 被清，第二次 get 返回 null → NPE → 内省器包成 401，用户吃一个莫名的登出。
		Cache.ValueWrapper cached = cache != null ? cache.get(username) : null;
		if (cached != null) {
			return (BaseUser) cached.get();
		}
		//缓存中无此用户信息，通过 API 接口查询
		R<AuthUserInfo> result = userApi.info(username, SecurityConstants.FROM_IN);
		if(!result.isOk()){
			throw new RuntimeException(result.getMsg());
		}
		AuthUserInfo info = result.getData();
		if(info == null){
			throw new RuntimeException("账号或密码错误");
		}
		UserDetails userDetails = getUserDetails(info);
		if(userDetails.isAccountNonLocked()){
			//合法用户，放入缓存
			cache.put(username, userDetails);
		}
		return userDetails;
	}

	/**
	 * 构建userdetails
	 *
	 * @param info 用户信息
	 * @return
	 */
	public UserDetails getUserDetails(AuthUserInfo info) {

		Set<String> dbAuthsSet = new HashSet<>();

		if (ArrayUtil.isNotEmpty(info.getRoles())) {
			// 获取角色
			Arrays.stream(info.getRoles()).forEach(role -> dbAuthsSet.add(SecurityConstants.ROLE + role));
			// 获取资源
			dbAuthsSet.addAll(Arrays.asList(info.getPermissions()));

		}

		Collection<GrantedAuthority> authorities = AuthorityUtils
				.createAuthorityList(dbAuthsSet.toArray(new String[0]));
		// [B5] 契约由 UserInfo（内含 SysUser 实体）治成 AuthUserInfo 投影。字段一一对应，
		// 下面这段一行没动 —— 鉴权链路上每改一行都是一次登录故障的机会。
		AuthUserIdentity user = info.getUser();
		boolean enabled = StrUtil.equals(user.getLockFlag(), CommonConstants.STATUS_NORMAL);
		// 首启随机口令 / 后台重置之后必须先改密：凭证过期，密码校验通过后由 Security 拒绝发令牌（CredentialsExpiredException）
		boolean credentialsNonExpired = !StrUtil.equals(user.getPasswordChangeRequired(), CommonConstants.YES);
		// 构造security用户
		return new BaseUser(user.getId(), user.getOrganId(),user.getType(), user.getTenantId(), user.getUsername(), SecurityConstants.BCRYPT + user.getPassword(),user.getTenantIds(), enabled,
				true, credentialsNonExpired, enabled, authorities);
	}

	@Override
	public int getOrder() {
		return Integer.MIN_VALUE;
	}
}
