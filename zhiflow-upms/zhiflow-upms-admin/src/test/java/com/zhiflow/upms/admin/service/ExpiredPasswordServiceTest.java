package com.zhiflow.upms.admin.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.security.login.LoginFailureLock;
import com.zhiflow.upms.admin.mapper.SysUserMapper;
import com.zhiflow.upms.common.dto.ExpiredPasswordChangeDTO;
import com.zhiflow.upms.common.entity.SysUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 首登 / 重置后改密：原口令对、带标记才能改；错口令计入登录失败锁定，锁了口令对也不行；不存在的账号与口令错同一句话。
 */
class ExpiredPasswordServiceTest {

	private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

	private final SysUserMapper mapper = mock(SysUserMapper.class);

	private final InMemoryRedis redis = new InMemoryRedis();

	private final LoginFailureLock lock = new LoginFailureLock(redis, 3, Duration.ofMinutes(15), Duration.ofMinutes(15));

	private final ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager(CacheConstants.USER_CACHE);

	private ExpiredPasswordService service;

	@BeforeAll
	static void initTableInfo() {
		MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
		assistant.setCurrentNamespace("test");
		TableInfoHelper.initTableInfo(assistant, SysUser.class);
	}

	@BeforeEach
	void setUp() {
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("loginFailureLock", lock);
		service = new ExpiredPasswordService(mapper, cacheManager, beans.getBeanProvider(LoginFailureLock.class));
		cacheManager.getCache(CacheConstants.USER_CACHE).put("admin", "stale");
	}

	@Test
	@DisplayName("带改密标记 + 原口令对：改成新密码、清标记、清用户缓存")
	void changesPasswordAndClearsFlag() {
		givenUser("admin", "Initial-Pass-16", "1");
		when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

		assertNull(service.change(dto("admin", "Initial-Pass-16", "Brand-New-Pass-1")));

		verify(mapper).update(isNull(), any(Wrapper.class));
		assertNull(cacheManager.getCache(CacheConstants.USER_CACHE).get("admin"));
	}

	@Test
	@DisplayName("原口令错、账号不存在：同一句话，都计入失败；连错到上限锁定，之后口令对也不放行")
	void wrongPasswordCountsTowardsLock() {
		givenUser("admin", "Initial-Pass-16", "1");
		when(mapper.getByNoTenant(any())).thenAnswer(inv -> {
			SysUser q = inv.getArgument(0);
			return "admin".equals(q.getUsername()) ? user("admin", "Initial-Pass-16", "1") : null;
		});

		assertEquals(ExpiredPasswordService.BAD_CREDENTIALS, service.change(dto("nobody", "wrong", "Brand-New-Pass-1")),
				"账号不存在与口令错误同一句话");
		assertEquals(ExpiredPasswordService.BAD_CREDENTIALS, service.change(dto("admin", "wrong", "Brand-New-Pass-1")));
		assertEquals(ExpiredPasswordService.BAD_CREDENTIALS, service.change(dto("admin", "wrong", "Brand-New-Pass-1")));
		assertTrue(service.change(dto("admin", "wrong", "Brand-New-Pass-1")).contains("账号已锁定"), "第 3 次失败上锁");
		assertTrue(service.change(dto("admin", "wrong", "Brand-New-Pass-1")).contains("账号已锁定"));

		assertTrue(service.change(dto("admin", "Initial-Pass-16", "Brand-New-Pass-1")).contains("账号已锁定"),
				"锁定期间口令对也不放行");
		verify(mapper, never()).update(any(), any(Wrapper.class));
	}

	@Test
	@DisplayName("没有改密标记的账号不能走这里（登录后在个人中心改）")
	void rejectsAccountsWithoutFlag() {
		givenUser("admin", "Current-Pass-1", "0");

		assertTrue(service.change(dto("admin", "Current-Pass-1", "Brand-New-Pass-1")).contains("无需在这里修改"));
		verify(mapper, never()).update(any(), any(Wrapper.class));
	}

	@Test
	@DisplayName("新密码不能与原口令相同；并发时只有一次改成功")
	void rejectsSamePasswordAndHandlesRace() {
		givenUser("admin", "Initial-Pass-16", "1");
		assertTrue(service.change(dto("admin", "Initial-Pass-16", "Initial-Pass-16")).contains("不能与原口令相同"));

		when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(0);
		assertTrue(service.change(dto("admin", "Initial-Pass-16", "Brand-New-Pass-1")).contains("已修改"));
	}

	private void givenUser(String username, String rawPassword, String flag) {
		when(mapper.getByNoTenant(any())).thenReturn(user(username, rawPassword, flag));
	}

	private static SysUser user(String username, String rawPassword, String flag) {
		SysUser u = new SysUser();
		u.setId("1");
		u.setUsername(username);
		u.setTenantId("-1");
		u.setPassword(ENCODER.encode(rawPassword));
		u.setPasswordChangeRequired(flag);
		return u;
	}

	private static ExpiredPasswordChangeDTO dto(String username, String password, String newPassword) {
		ExpiredPasswordChangeDTO d = new ExpiredPasswordChangeDTO();
		d.setUsername(username);
		d.setPassword(password);
		d.setNewPassword(newPassword);
		return d;
	}

	/** 只实现 LoginFailureLock 用到的操作；不用 Mockito mock 具体类。 */
	private static final class InMemoryRedis extends StringRedisTemplate {

		private final Map<String, String> data = new HashMap<>();

		private final Map<String, Long> ttl = new HashMap<>();

		@Override
		@SuppressWarnings("unchecked")
		public ValueOperations<String, String> opsForValue() {
			return (ValueOperations<String, String>) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { ValueOperations.class }, (proxy, method, args) -> switch (method.getName()) {
						case "increment" -> {
							long n = Long.parseLong(data.getOrDefault((String) args[0], "0")) + 1;
							data.put((String) args[0], Long.toString(n));
							yield n;
						}
						case "set" -> {
							data.put((String) args[0], (String) args[1]);
							ttl.put((String) args[0], ((Duration) args[2]).toSeconds());
							yield null;
						}
						default -> throw new UnsupportedOperationException(method.getName());
					});
		}

		@Override
		public Boolean expire(String key, Duration timeout) {
			ttl.put(key, timeout.toSeconds());
			return true;
		}

		@Override
		public Long getExpire(String key) {
			return data.containsKey(key) ? ttl.getOrDefault(key, -1L) : -2L;
		}

		@Override
		public Boolean delete(String key) {
			ttl.remove(key);
			return data.remove(key) != null;
		}
	}
}
