package com.zhiflow.common.security.component;

import com.zhiflow.common.core.constant.PathPrefixConstants;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * [P2.3-B] 安全链的两个口径——「管辖哪些路径」与「用哪套认证机制」——的唯一出处。
 *
 * <h2>为什么要有这个类</h2>
 * <p>改造前，{@code BaseResourceServerConfiguration} 是这么决定一个服务的安全姿态的：
 * <pre>
 * if ("zhiflow-mall-api".equals(SpringUtil.getProperty("spring.application.name"))) { 全放行 }
 * else { releaseUrls 放行 + 其余要 Bearer token }
 * </pre>
 * 两个问题：
 * <ol>
 * <li><b>按应用名字符串分支</b>：一个共享库里写死某个服务的名字，改名 / 拆服务 /
 *     合并进阶段 3 的单体时，安全姿态会<b>静默</b>翻转，而编译期与日志都发现不了；</li>
 * <li><b>没有管辖范围</b>：这条链的 matcher 是「任意请求」。阶段 3 把 mall-api 与 mall-admin
 *     装进同一个进程后，先注册的那条链会把另一个服务的端点一并吞掉 ——
 *     mall-api 那条是<b>全放行</b>，吞掉 mall-admin 就是把整个后台开成匿名可达。</li>
 * </ol>
 *
 * <p>所以两个口径都改成显式的：管辖范围取自 {@code zhiflow.path-prefix.value}
 * （与 MVC 加前缀用的是同一个值，不可能对不上），认证机制取自
 * {@code zhiflow.security.authentication-mode}，由服务在自己的 {@code application.yml} 里声明。
 *
 * <p><b>默认值刻意是 {@code oauth2}</b>：没声明的服务按资源服务器处理（要 Bearer token）。
 * 反过来默认成全放行的话，任何一个漏配都是一次静默的鉴权全失效。
 *
 * <h2>⚠️ 属性名的唯一出处是 common-core</h2>
 * <p>{@code zhiflow.path-prefix.value} 的绑定宿主是 {@code zhiflow-common-data} 的
 * {@code PathPrefixProperties}，而**读**它的地方散在三个模块：安全链管辖范围（本类）、
 * 白名单补前缀（{@code PermitAllUrlProperties}、common-portal 的 {@code WebConfig}）、
 * 拦截器 pattern 补前缀（mall-admin 的 {@code AnalyticsUnknownParamConfig}）。
 * 所以字符串只在 {@link PathPrefixConstants} 写一份、其余全部引用它 —— 各写一份写岔了的表现是
 * 「MVC 加了前缀、这一处却还按老路径匹配」，正是 2026-08-15 auth 那次 12 个端点变 302 的形态。
 * {@code zhiflow-arch-test} 的 {@code SecurityScopeConsistencyTest} 守住它与绑定宿主一致。
 */
public final class SecurityScope {

	/** 服务内部路径前缀（与 MVC {@code addPathPrefix} 同源）。口径来自 common-core，不另写一份字符串。 */
	public static final String PATH_PREFIX_PROPERTY = PathPrefixConstants.PROPERTY;

	/**
	 * 安全链管辖范围的**覆盖项**，只在「命名空间不是由 {@code addPathPrefix} 加出来的」时候用。
	 *
	 * <p>唯一的用例是 im-server：它的 {@code /api} 是<b>写在 controller 源码里</b>的
	 * （改造前就已经是目标形态），所以它没有也不能有 {@code zhiflow.path-prefix.value} ——
	 * 配上去会让 {@code addPathPrefix} 再加一层，变成 {@code /api/api/im/**}。
	 * 但它的安全链同样应当只管自己的命名空间，否则阶段 3 合并进单体时，
	 * 先注册的那条「任意请求」的链会把别的模块的端点一并吞掉。
	 */
	public static final String SECURITY_PREFIX_PROPERTY = "zhiflow.security.path-prefix";

	/**
	 * 给 {@code @Value} 用的占位符：先看安全链的覆盖项，再退到 MVC 前缀，都没有则空串
	 * （= 本服务没做前缀改造，安全链管辖全部请求）。
	 */
	public static final String PATH_PREFIX_PLACEHOLDER = "${" + SECURITY_PREFIX_PROPERTY + ":${" + PATH_PREFIX_PROPERTY
			+ ":}}";

	/** 与 {@link #PATH_PREFIX_PLACEHOLDER} 判定一致的注册条件表达式。两者给出不同答案会导致启动失败。 */
	public static final String HAS_PREFIX_CONDITION = "'" + PATH_PREFIX_PLACEHOLDER + "'.trim().length() > 0";

	/** 本服务命名空间由哪套认证机制管辖。 */
	public static final String AUTHENTICATION_MODE_PROPERTY = "zhiflow.security.authentication-mode";

	/** 默认：OAuth2 资源服务器，白名单之外一律要 Bearer token。 */
	public static final String MODE_OAUTH2 = "oauth2";

	/**
	 * C 端门户：认证由 {@code @ApiLogin} + {@code ThirdSessionInterceptor} 在 Spring MVC 层做，
	 * Security 层对本服务命名空间放行。目前只有 {@code zhiflow-mall-api} 是这个模式。
	 */
	public static final String MODE_API_LOGIN = "api-login";

	/** 给 {@code @Value} 用的占位符，默认 {@link #MODE_OAUTH2}（漏配时按更严的那一边走）。 */
	public static final String AUTHENTICATION_MODE_PLACEHOLDER = "${" + AUTHENTICATION_MODE_PROPERTY + ":"
			+ MODE_OAUTH2 + "}";

	private SecurityScope() {
	}

	/** 本服务是否做了前缀改造。空 = 没做，安全链的管辖范围就是「全部请求」。 */
	public static boolean hasPrefix(String prefix) {
		return PathPrefixConstants.hasPrefix(prefix);
	}

	/**
	 * 去空白、补前导斜杠、去尾部斜杠。{@code pay} / {@code /pay/} 与 {@code /pay} 必须是同一个范围。
	 *
	 * <p>实现只有一份（{@link PathPrefixConstants#normalize}）：安全链圈范围、白名单补前缀、
	 * 拦截器 pattern 补前缀这三处必须对同一个配置值得出同一个答案，各写一份迟早写岔。
	 */
	public static String normalizePrefix(String prefix) {
		return PathPrefixConstants.normalize(prefix);
	}

	/**
	 * 管辖范围：{@code /pay} 本身与 {@code /pay/**}。
	 *
	 * <p><b>两条都要</b>：只写 {@code /pay/**} 时 {@code /pay} 这个裸路径不归本链管，
	 * 会掉到兜底链去；只写 {@code /pay} 则只有裸路径归本链管。
	 *
	 * <p>用 {@link PathPatternRequestMatcher}（Security 7 已删 AntPathRequestMatcher）而不是 {@code securityMatcher(String...)}：
	 * 后者在 classpath 有 spring-webmvc 时会选 {@code MvcRequestMatcher}，
	 * 匹配语义取决于 servlet 映射与 {@code HandlerMappingIntrospector}，
	 * 而本仓的白名单（{@code PermitAllUrlProperties}）一直是按 Ant 语义算的。
	 * 同一条链里两种匹配语义混用，出问题时极难定位。
	 */
	public static RequestMatcher jurisdiction(String prefix) {
		String value = normalizePrefix(prefix);
		if (value.isEmpty()) {
			throw new IllegalArgumentException("没有配置前缀时不该圈管辖范围——那样会把全部请求漏在安全链之外");
		}
		PathPatternRequestMatcher.Builder matchers = PathPatternRequestMatcher.withDefaults();
		return new OrRequestMatcher(matchers.matcher(value), matchers.matcher(value + "/**"));
	}

	/** 是否是 C 端门户模式（Security 层放行、认证交给 MVC 层的 {@code @ApiLogin}）。 */
	public static boolean isApiLoginMode(String mode) {
		return mode != null && MODE_API_LOGIN.equalsIgnoreCase(mode.trim());
	}
}
