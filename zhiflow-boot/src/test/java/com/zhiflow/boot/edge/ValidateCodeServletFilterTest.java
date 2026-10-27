package com.zhiflow.boot.edge;

import cloud.tianai.captcha.cache.CacheStore;
import cloud.tianai.captcha.common.AnyMap;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录验证码校验（网关 {@code ValidateCodeGatewayFilter} 的移植）。
 *
 * <p>redis 用「动态代理内存版 RedisTemplate」而不是 Mockito：本仓有
 * 「Mockito mock 具体类在 JDK25 下污染 TypeCache、拖垮同批次后续测试」的前科，
 * 且内存版让「写入方（PhoneServiceImpl 同款 set）→ 校验方」走的是真序列化路径。
 *
 * <h2>反向验证</h2>
 * <ul>
 * <li>把 checkCode 的滑块分支改成「查不到也放行」→
 *     {@code passwordGrantWithoutSliderTokenIsRejected} 变红（这正是「验证码静默失效
 *     比报错更糟」那条事故形态）；</li>
 * <li>把 ignore-clients 判断删掉 → {@code ignoredClientSkipsCaptcha} 变红
 *     （C 端 api 客户端与权限矩阵金丝雀全被验证码闸死）；</li>
 * <li>把 getAndRemoveCache 换成 getCache（不核销）→ {@code sliderTokenIsSingleUse} 变红
 *     （滑块令牌可重放 = 防爆破闸名存实亡）；</li>
 * <li>把「refresh_token 跳过」删掉 → 对应用例变红（刷新令牌链路全断）。</li>
 * </ul>
 */
class ValidateCodeServletFilterTest {

	private static final String SLIDER_KEY_PREFIX = "captcha:secondary:";

	private final Map<String, byte[]> redisData = new HashMap<>();

	private final Map<String, AnyMap> captchaData = new HashMap<>();

	private final RedisTemplate<String, Object> redisTemplate = inMemoryRedisTemplate(this.redisData);

	private final GatewayParityProperties properties = new GatewayParityProperties();

	private final ValidateCodeServletFilter filter;

	ValidateCodeServletFilterTest() {
		// 与 zhiflow-gateway-dev.yml 模板逐字一致的豁免清单
		this.properties.getIgnoreClients().add("swagger");
		this.properties.getIgnoreClients().add("api");
		this.filter = new ValidateCodeServletFilter(this.properties, new JsonMapper(), this.redisTemplate,
				mapBackedCacheStore(this.captchaData));
	}

	// ==================================================================================
	// 豁免逻辑（金丝雀依赖，逐字保留）
	// ==================================================================================

	@Test
	@DisplayName("非登录路径直通，不碰任何验证码状态")
	void nonTokenPathPassesThrough() throws Exception {
		MockFilterChain chain = new MockFilterChain();
		this.filter.doFilter(request("/mallapi/goodsspu/page", "password", "any-client"), new MockHttpServletResponse(),
				chain);
		assertNotNull(chain.getRequest());
	}

	@Test
	@DisplayName("⭐ ignore-clients（api/swagger）免验证码 —— 权限矩阵金丝雀靠这条活着")
	void ignoredClientSkipsCaptcha() throws Exception {
		MockHttpServletRequest request = request("/oauth2/token", "password", "api");
		// 刻意不给 code：豁免失效的话会 428「验证码不能为空」
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		this.filter.doFilter(request, response, chain);

		assertNotNull(chain.getRequest(), "api 客户端被验证码闸死 —— C 端登录与权限矩阵金丝雀全断");
		assertEquals(200, response.getStatus());
	}

	@Test
	@DisplayName("refresh_token 授权免验证码")
	void refreshTokenGrantSkipsCaptcha() throws Exception {
		MockFilterChain chain = new MockFilterChain();
		this.filter.doFilter(request("/oauth2/token", "refresh_token", "test"), new MockHttpServletResponse(), chain);
		assertNotNull(chain.getRequest(), "刷新令牌被验证码闸死 —— 全端会话续期全断");
	}

	// ==================================================================================
	// 密码登录：滑块验证码（captcha:secondary:*，一次性核销）
	// ==================================================================================

	@Test
	@DisplayName("密码登录 + 有效滑块令牌 → 放行并核销令牌")
	void passwordGrantWithValidSliderTokenPasses() throws Exception {
		this.captchaData.put(SLIDER_KEY_PREFIX + "tok-1", new AnyMap());

		MockHttpServletRequest request = request("/oauth2/token", "password", "test");
		request.setParameter("code", "tok-1");

		MockFilterChain chain = new MockFilterChain();
		this.filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertNotNull(chain.getRequest(), "有效滑块令牌被拒");
		assertFalse(this.captchaData.containsKey(SLIDER_KEY_PREFIX + "tok-1"), "令牌没被核销");
	}

	@Test
	@DisplayName("⭐ 密码登录没有/伪造滑块令牌 → 428 拒绝（缺了这道闸 = 防爆破静默消失）")
	void passwordGrantWithoutSliderTokenIsRejected() throws Exception {
		MockHttpServletRequest request = request("/oauth2/token", "password", "test");
		request.setParameter("code", "forged-token");

		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		this.filter.doFilter(request, response, chain);

		assertNull(chain.getRequest(), "伪造滑块令牌被放行 —— 密码爆破无阻力");
		assertEquals(428, response.getStatus(), "拒绝状态码必须与网关一致（PRECONDITION_REQUIRED）");
		assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("滑块验证码未通过"));
	}

	@Test
	@DisplayName("滑块令牌一次性：第二次提交同一令牌被拒")
	void sliderTokenIsSingleUse() throws Exception {
		this.captchaData.put(SLIDER_KEY_PREFIX + "tok-2", new AnyMap());

		MockHttpServletRequest first = request("/oauth2/token", "password", "test");
		first.setParameter("code", "tok-2");
		this.filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());

		MockHttpServletRequest replay = request("/oauth2/token", "password", "test");
		replay.setParameter("code", "tok-2");
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		this.filter.doFilter(replay, response, chain);

		assertNull(chain.getRequest(), "滑块令牌可重放");
		assertEquals(428, response.getStatus());
	}

	@Test
	@DisplayName("code 为空 → 428「验证码不能为空」")
	void blankCodeIsRejected() throws Exception {
		MockHttpServletRequest request = request("/oauth2/token", "password", "test");

		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		this.filter.doFilter(request, response, chain);

		assertNull(chain.getRequest());
		assertEquals(428, response.getStatus());
		assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("验证码不能为空"));
	}

	@Test
	@DisplayName("Basic 头缺失不豁免、照常校验（拒绝面只紧不松）")
	void missingBasicHeaderIsStillGated() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
		request.setRequestURI("/oauth2/token");
		request.setParameter("grant_type", "password");
		// 无 Authorization 头、无 code

		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		this.filter.doFilter(request, response, chain);

		assertNull(chain.getRequest(), "缺 Basic 头的请求被当成豁免客户端放行了");
		assertEquals(428, response.getStatus());
	}

	@Test
	@DisplayName("外部以 /auth/oauth2/token 形态直达也命中（子串判据与网关一致）")
	void authPrefixedTokenPathIsAlsoGated() throws Exception {
		MockHttpServletRequest request = request("/auth/oauth2/token", "password", "test");
		request.setParameter("code", "forged");

		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		this.filter.doFilter(request, response, chain);

		assertNull(chain.getRequest());
		assertEquals(428, response.getStatus());
	}


	// ==================================================================================
	// 协作者：无 Mockito 的内存版 redis / captcha store
	// ==================================================================================

	private static MockHttpServletRequest request(String path, String grantType, String clientId) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
		request.setRequestURI(path);
		request.setContentType("application/x-www-form-urlencoded");
		request.setParameter("grant_type", grantType);
		request.addHeader("Authorization", "Basic " + Base64.getEncoder()
			.encodeToString((clientId + ":secret").getBytes(StandardCharsets.UTF_8)));
		return request;
	}

	private static CacheStore mapBackedCacheStore(Map<String, AnyMap> data) {
		return new CacheStore() {

			@Override
			public AnyMap getCache(String key) {
				return data.get(key);
			}

			@Override
			public AnyMap getAndRemoveCache(String key) {
				return data.remove(key);
			}

			@Override
			public boolean setCache(String key, AnyMap value, Long expire, TimeUnit timeUnit) {
				data.put(key, value);
				return true;
			}

			@Override
			public Long incr(String key, long delta, Long expire, TimeUnit timeUnit) {
				return null;
			}

			@Override
			public Long getLong(String key) {
				return null;
			}

		};
	}

	/**
	 * 动态代理内存版 RedisTemplate：只实现 filter 会走到的命令
	 * （exists / get / set / setEx / pSetEx / del），序列化路径全真
	 * （String key + JDK value，与实际部署的「String key + JSON value」同样能把 Integer round-trip 回来）。
	 */
	private static RedisTemplate<String, Object> inMemoryRedisTemplate(Map<String, byte[]> store) {
		InvocationHandler connectionHandler = (proxy, method, args) -> switch (method.getName()) {
			case "keyCommands", "stringCommands" -> proxy;
			case "exists" -> args[0] instanceof byte[] single ? store.containsKey(key(single)) : null;
			case "get" -> store.get(key((byte[]) args[0]));
			case "set" -> {
				store.put(key((byte[]) args[0]), (byte[]) args[1]);
				yield Boolean.TRUE;
			}
			case "setEx", "pSetEx" -> {
				store.put(key((byte[]) args[0]), (byte[]) args[2]);
				yield Boolean.TRUE;
			}
			case "del" -> {
				long removed = 0;
				for (byte[] rawKey : (byte[][]) args[0]) {
					removed += store.remove(key(rawKey)) != null ? 1 : 0;
				}
				yield removed;
			}
			case "close" -> null;
			case "isClosed", "isPipelined", "isQueueing" -> Boolean.FALSE;
			case "toString" -> "InMemoryRedisConnection";
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals" -> proxy == args[0];
			default -> defaultValue(method.getReturnType());
		};
		RedisConnection connection = (RedisConnection) Proxy.newProxyInstance(
				ValidateCodeServletFilterTest.class.getClassLoader(), new Class<?>[] { RedisConnection.class },
				connectionHandler);

		InvocationHandler factoryHandler = (proxy, method, args) -> switch (method.getName()) {
			case "getConnection" -> connection;
			case "getConvertPipelineAndTxResults" -> Boolean.FALSE;
			case "toString" -> "InMemoryRedisConnectionFactory";
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals" -> proxy == args[0];
			default -> defaultValue(method.getReturnType());
		};
		RedisConnectionFactory factory = (RedisConnectionFactory) Proxy.newProxyInstance(
				ValidateCodeServletFilterTest.class.getClassLoader(), new Class<?>[] { RedisConnectionFactory.class },
				factoryHandler);

		RedisTemplate<String, Object> template = new RedisTemplate<>();
		template.setConnectionFactory(factory);
		template.setKeySerializer(new StringRedisSerializer());
		template.setHashKeySerializer(new StringRedisSerializer());
		template.afterPropertiesSet();
		return template;
	}

	private static String key(byte[] raw) {
		return new String(raw, StandardCharsets.ISO_8859_1);
	}

	private static Object defaultValue(Class<?> type) {
		if (!type.isPrimitive() || type == void.class) {
			return null;
		}
		if (type == boolean.class) {
			return Boolean.FALSE;
		}
		if (type == long.class) {
			return 0L;
		}
		if (type == double.class) {
			return 0d;
		}
		if (type == float.class) {
			return 0f;
		}
		return 0;
	}

}
