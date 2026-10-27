package com.zhiflow.common.data.tenant;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.api.upms.UserApi;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.security.component.AuthorizationSnapshotCache;
import com.zhiflow.common.security.component.PreloadedAuthorization;
import com.zhiflow.common.security.component.TokenTenant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.web.filter.GenericFilterBean;


/**
 * 租户上下文过滤器
 * 从请求中提取租户信息并设置到上下文
 *
 * 注意：
 * - Filter 顺序由 TenantFilterConfiguration 中的 FilterRegistrationBean 控制
 * - 不再使用 @Order 注解，避免重复配置
 *
 * <h2>压测优化（2026-09-30）</h2>
 * <ul>
 * <li><b>A1</b>：读到的授权记录放进 request attribute（{@link PreloadedAuthorization}），令牌内省器在令牌值
 *     逐字相等时直接复用，同一个 34 KB 的键不再读两遍。本过滤器自己的行为不变。</li>
 * <li><b>A2（开关默认关）</b>：命中内省缓存时只做一次 {@code EXISTS} 确认令牌仍在，租户三元组取自缓存
 *     （{@link TokenTenant}）。<b>切租户仍每请求现算</b>：缓存的是令牌里的原始值，不是算出来的租户。
 *     {@code EXISTS} 为假或没命中就走原逻辑（含旧格式 KEYS 兜底、回退 tenant-id 头），与今天一致。</li>
 * </ul>
 *
 */
@Slf4j
public class TenantContextHolderFilter extends GenericFilterBean {

	/** 与 BaseBearerTokenExtractor 读的网址参数同名（GET 可用令牌参数，WebSocket 握手等场景在用）。 */
	static final String ACCESS_TOKEN_PARAMETER = "access_token";

	private final RedisTemplate<String, Object> redisTemplate;
	private final UserApi userApi;

	/** 压测优化 A2 的内省缓存；null = 本进程没有（auth、测试）。是否启用由开关每请求现读。 */
	@Nullable
	private final AuthorizationSnapshotCache snapshotCache;

	/**
	 * 构造函数
	 * @param redisTemplate OAuth2 Redis 模板
	 * @param userApi 用户 API 客户端
	 */
	public TenantContextHolderFilter(RedisTemplate<String, Object> redisTemplate, UserApi userApi) {
		this(redisTemplate, userApi, null);
	}

	/**
	 * @param snapshotCache 压测优化 A2 的内省缓存，可为 null
	 */
	public TenantContextHolderFilter(RedisTemplate<String, Object> redisTemplate, UserApi userApi,
			@Nullable AuthorizationSnapshotCache snapshotCache) {
		this.redisTemplate = redisTemplate;
		this.userApi = userApi;
		this.snapshotCache = snapshotCache;
	}

	/**
	 * 进出都清租户，出口放在 finally 里。
	 *
	 * <p>原先只在 {@code filterChain.doFilter} 正常返回后清：链路一抛异常就不清，而 Tomcat 平台线程是复用的，
	 * 租户会带进这条线程接的下一个请求。下面「token 解析不出租户才回退到 tenant-id 头」的判断
	 * （{@code isBlank(getTenantId())}）正好依赖入口时上下文是空的 —— 残留的旧租户会让它直接沿用旧值。
	 */
	@Override
	@SneakyThrows
	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) {
		TenantContextHolder.clear();
		try {
			resolveTenantAndProceed(servletRequest, servletResponse, filterChain);
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	private void resolveTenantAndProceed(ServletRequest servletRequest, ServletResponse servletResponse,
			FilterChain filterChain) throws Exception {
		HttpServletRequest request = (HttpServletRequest) servletRequest;
		HttpServletResponse response = (HttpServletResponse) servletResponse;
		// 租户解析：带了令牌就按令牌签发时的租户（默认租户 1，超管是系统租户 -1）；
		// 其余一律默认租户。不读 tenant-id / switch-tenant-id 头，也不再有超管切换租户。
		// 令牌识别规则与 BaseBearerTokenExtractor 一致，见 resolveTokenValue。
		String tenantId = null;
		String tokenValue = resolveTokenValue(request);
		if (StrUtil.isNotBlank(tokenValue)) {
			TokenTenant tokenTenant = resolveTokenTenant(request, tokenValue);
			tenantId = tokenTenant == null ? null : tokenTenant.tenantId();
		}
		TenantContextHolder.setTenantId(StrUtil.isNotBlank(tenantId) ? tenantId : CommonConstants.DEFAULT_TENANT_ID);

		filterChain.doFilter(request, response);
	}

	/**
	 * 取请求里的令牌，规则必须与资源服务器的 {@code BaseBearerTokenExtractor} 完全一致：
	 * {@code Authorization} 头按不区分大小写的 {@code bearer} 前缀识别；GET 请求再看 {@code access_token} 网址参数。
	 *
	 * <p>2026-09-30 跨租户越权：这里原先只认大小写敏感的 {@code Bearer} 头，而解析器还认小写与网址参数。
	 * 两边一错位，请求就会「按 A 租户的令牌认证、却按请求自带的 tenant-id 头落到 B 租户」。
	 * 这里只负责「认出令牌」，令牌格式对不对仍由解析器判（格式不对它会 401），所以不做格式校验。
	 */
	@Nullable
	static String resolveTokenValue(HttpServletRequest request) {
		String authorization = request.getHeader(CommonConstants.AUTHORIZATION);
		String bearer = OAuth2AccessToken.TokenType.BEARER.getValue();
		if (StrUtil.startWithIgnoreCase(authorization, bearer)) {
			String token = authorization.substring(bearer.length()).trim();
			return token.isEmpty() ? null : token;
		}
		if ("GET".equals(request.getMethod())) {
			String[] values = request.getParameterValues(ACCESS_TOKEN_PARAMETER);
			if (values != null && values.length == 1 && StrUtil.isNotBlank(values[0])) {
				return values[0].trim();
			}
		}
		return null;
	}

	/**
	 * 取令牌里签发时的租户三元组；令牌无效（过期 / 不存在 / 读不出）或记录里没有 principal 时返回 null。
	 *
	 * <p>A2 开着且命中内省缓存：一次 {@code EXISTS} 确认令牌仍在，三元组取自缓存，
	 * 并告诉内省器「本请求已确认存在」。否则走原逻辑，并把读到的记录（可能为 null）交给内省器复用（A1）。
	 */
	@Nullable
	private TokenTenant resolveTokenTenant(HttpServletRequest request, String tokenValue) {
		if (this.snapshotCache != null && this.snapshotCache.isEnabled()) {
			AuthorizationSnapshotCache.Snapshot snapshot = this.snapshotCache
				.getIfAlive(AuthorizationSnapshotCache.keyOf(tokenValue), tokenValue, null);
			if (snapshot != null) {
				PreloadedAuthorization.verifiedExisting(request, tokenValue);
				return snapshot.tokenTenant();
			}
		}
		// 直查无租户后缀键，命中免 KEYS 全库扫描；未命中回退旧格式 KEYS（行为与改动前一致）。
		// 服务端 save 仍双写带后缀键，所以回退路径始终可用，本优化不会造成鉴权失败。
		OAuth2Authorization oAuth2Authorization = readAuthorizationTolerant(
				StrUtil.concat(false, CacheConstants.OAUTH_CLIENT_CACHE, "::", tokenValue));
		if (oAuth2Authorization == null) {
			var legacyKeys = redisTemplate.keys(StrUtil.concat(false, CacheConstants.OAUTH_CLIENT_CACHE, "::", tokenValue, ":*"));
			if (legacyKeys != null && !legacyKeys.isEmpty()) {
				oAuth2Authorization = readAuthorizationTolerant(legacyKeys.iterator().next());
			}
		}
		// [A1] 交给内省器：令牌值逐字相等时它直接用这次的结果，不再读第二遍
		PreloadedAuthorization.loaded(request, tokenValue, oAuth2Authorization);
		return oAuth2Authorization == null ? null : TokenTenant.from(oAuth2Authorization);
	}

	/**
	 * 读 Redis 里 JDK 序列化的授权记录；反序列化失败按"无此 token"处理并删掉坏键。
	 *
	 * <p>跨大版本升级（2026-09-11 升级 Boot 4 / Authorization Server 7 时实测）后，
	 * {@code OAuth2Authorization} 的 serialVersionUID 变了，升级前签发的 token 一律
	 * {@code InvalidClassException}。本过滤器排在安全链之前，这里若把异常抛出去，客户端拿到的是 500
	 * （当成服务故障不会重登）；按"取不到租户、往下走安全链"处理，后续由令牌内省给出 401，客户端正常重登。
	 */
	private OAuth2Authorization readAuthorizationTolerant(String key) {
		try {
			return (OAuth2Authorization) redisTemplate.opsForValue().get(key);
		}
		catch (org.springframework.data.redis.serializer.SerializationException | ClassCastException ex) {
			log.warn("租户过滤器：授权记录反序列化失败，按无效令牌处理并清除 key={} cause={}", abbreviateKey(key),
					ex.getCause() != null ? ex.getCause().toString() : ex.toString());
			try {
				redisTemplate.delete(key);
			}
			catch (RuntimeException ignore) {
				// 删不掉不影响主流程
			}
			return null;
		}
	}

	/** 日志里只保留 token 前缀：这些键是失效令牌，但仍不该把完整值写进日志。 */
	private static String abbreviateKey(String key) {
		int i = key.lastIndexOf("::");
		String prefix = i >= 0 ? key.substring(0, i + 2) : "";
		String tail = i >= 0 ? key.substring(i + 2) : key;
		return prefix + (tail.length() > 12 ? tail.substring(0, 12) + "…" : tail);
	}
}
