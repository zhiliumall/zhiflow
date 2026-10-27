package com.zhiflow.common.security.component;

import jakarta.servlet.ServletRequest;
import org.springframework.lang.Nullable;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;

/**
 * 同一个请求里，{@code TenantContextHolderFilter} 已经对这个令牌做过的那次 Redis 查询结果，
 * 交给令牌内省器复用（压测优化 A1 / A2）。
 *
 * <h2>为什么需要它</h2>
 * <p>改造前每个带 {@code Authorization: Bearer} 的后台请求，会把同一个直查键
 * {@code public:token::access_token::<token>}（JDK 序列化，生产实测约 34 KB）读两遍、反序列化两遍：
 * 一次在租户过滤器（取租户），一次在内省器（{@code findByToken}）。两次相隔亚毫秒，
 * 合成一次在语义上等价 —— 唯一的差别是这亚毫秒窗口里的登出不再被本请求感知，下一次请求照样 401。
 *
 * <h2>只在令牌值逐字相等时复用</h2>
 * <p>过滤器只读 {@code Authorization} 头（{@code replace("Bearer", "")} 的取法），而内省器的令牌来自
 * {@code BaseBearerTokenExtractor}（GET 时还接受 {@code ?access_token=}）；IM / 直播的握手也会直接调内省器。
 * 两边拿到的字符串不一定相同，所以 {@link #find} 必须比对令牌值，不相等就当没有。
 *
 * <h2>两种形态</h2>
 * <ul>
 * <li>{@link #loaded}：过滤器完整查过一遍（直查键 + 旧格式 KEYS 兜底），{@code authorization} 为 null
 *     表示「查过了，不存在」—— 内省器直接复用，不再查 Redis；</li>
 * <li>{@link #verifiedExisting}：开了 A2 且过滤器命中进程内缓存时，只做了一次 {@code EXISTS}，
 *     确认令牌还在，但没有读授权记录本身。内省器可以据此信任自己的进程内缓存条目，
 *     但如果自己没命中，仍要自己去读。</li>
 * </ul>
 *
 * <p>放在 common-security 是因为依赖方向：common-data 依赖 common-security，过滤器可以引用本类，反之不行。
 */
public final class PreloadedAuthorization {

	/** request attribute 名。 */
	public static final String REQUEST_ATTRIBUTE = PreloadedAuthorization.class.getName();

	private final String token;

	private final boolean loaded;

	@Nullable
	private final OAuth2Authorization authorization;

	private PreloadedAuthorization(String token, boolean loaded, @Nullable OAuth2Authorization authorization) {
		this.token = token;
		this.loaded = loaded;
		this.authorization = authorization;
	}

	/** 过滤器已完整查过这个令牌；{@code authorization == null} 表示查过了、不存在。 */
	public static void loaded(ServletRequest request, String token, @Nullable OAuth2Authorization authorization) {
		request.setAttribute(REQUEST_ATTRIBUTE, new PreloadedAuthorization(token, true, authorization));
	}

	/** 过滤器只用 {@code EXISTS} 确认了令牌还在，没有读授权记录本身（A2 命中进程内缓存时）。 */
	public static void verifiedExisting(ServletRequest request, String token) {
		request.setAttribute(REQUEST_ATTRIBUTE, new PreloadedAuthorization(token, false, null));
	}

	/**
	 * 取本请求里针对<b>同一个令牌值</b>的查询结果；没有、或令牌值不同，返回 null。
	 */
	@Nullable
	public static PreloadedAuthorization find(@Nullable ServletRequest request, String token) {
		if (request == null || token == null) {
			return null;
		}
		Object value = request.getAttribute(REQUEST_ATTRIBUTE);
		if (value instanceof PreloadedAuthorization preloaded && token.equals(preloaded.token)) {
			return preloaded;
		}
		return null;
	}

	/** 是否完整查过（可以直接拿 {@link #getAuthorization()} 当 {@code findByToken} 的结果用）。 */
	public boolean isLoaded() {
		return this.loaded;
	}

	/** 完整查询的结果；{@link #isLoaded()} 为 false 时恒为 null，不能当「不存在」解读。 */
	@Nullable
	public OAuth2Authorization getAuthorization() {
		return this.authorization;
	}

	/** 本请求内已确认令牌仍在 Redis 里（读到了记录，或 {@code EXISTS} 为真）。 */
	public boolean exists() {
		return !this.loaded || this.authorization != null;
	}

}
