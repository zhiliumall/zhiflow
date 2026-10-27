package com.zhiflow.common.core.constant;

import java.util.ArrayList;
import java.util.List;

/**
 * [P2.3] 服务内部路径前缀的**唯一口径**：属性名、归一化、以及「给写死的路径字面量补前缀」。
 *
 * <h2>为什么需要它</h2>
 * <p>{@code zhiflow.path-prefix.value} 打开后，本服务 controller 的真实路径统统多一段前缀
 * （{@code /orderinfo/x} → {@code /mallapi/orderinfo/x}）。**从 handler 映射现算的东西会自动跟上**
 * （{@code PermitAllUrlProperties} 就是这么算白名单的），但**代码里写死的路径字面量不会** ——
 * 它们会在开前缀那一刻整片失配，而且失配得毫无声息。
 *
 * <p>本仓有三处这样的字面量，全都不是「样式问题」而是会出真事故的：
 * <ol>
 * <li>{@link PublicUrlConstants#PORTAL_PUBLIC_URLS} 同时是
 *     {@code ThirdSessionInterceptor} 的 {@code excludePathPatterns} ——
 *     里面躺着 {@code /orderinfo/notify-order-*}、{@code /vipuserrecharge/notify-*}、
 *     {@code /logistics/callback/*} 这些**支付与物流回调**，还有四端登录都要用的
 *     {@code /captcha/**}。失配的后果按调用方形态分两种：带 {@code client-type: H5/H5_PC/APP}
 * 且不带 {@code tenant-id} 头的请求会被拦截器判 60004 拒绝（**四端登录要用的
 * {@code /captcha/**} 正属此类**）；支付回调不带 {@code client-type} 头、走的是
 * 不拒绝的那条分支，不会被打断，但会被塞进默认租户上下文。
 * 另一侧（permitAll 白名单）在 mall-api 的 api-login 模式下不受影响 —— 安全层本来就全放行。</li>
 * <li>mall-admin 的 {@code AnalyticsUnknownParamConfig} 用 {@code /analytics/**} 等
 *     注册「未识别查询参数一律 400」的拦截器。失配 = 这道闸<b>静默失效</b>；</li>
 * <li>安全链的管辖范围（见 common-security 的 {@code SecurityScope}）。</li>
 * </ol>
 *
 * <p>⚠️ 上面前两类**权限矩阵一条都探不到**：回调全是 POST / 含 {@code notify} 动词，
 * captcha 也是 POST，而「未识别参数」是查询串层面的事。所以这类改动的判据只能是
 * 单元测试 + 逐条手工探测，不能指望量尺。
 */
public final class PathPrefixConstants {

	/** 服务内部路径前缀。空 = 本服务没做前缀改造。 */
	public static final String PROPERTY = "zhiflow.path-prefix.value";

	/** 给 {@code @Value} 用的占位符（编译期常量，可以直接写进注解）。 */
	public static final String PLACEHOLDER = "${" + PROPERTY + ":}";

	/**
	 * 单体的多命名空间列表（{@link PathNamespace}）。**只有形态一 {@code all} 会配**。
	 *
	 * <p>多进程形态下这个键不存在，MVC 侧与安全侧都走各自的单值老路径 ——
	 * 「多进程零变化」由「新代码根本不执行」保证，而不是靠逻辑等价论证。
	 */
	public static final String NAMESPACES_PROPERTY = "zhiflow.path-prefix.namespaces";

	/**
	 * 判「是不是多命名空间形态」的条件表达式，供 {@code @ConditionalOnExpression} 用。
	 *
	 * <p>⚠️ 判的是<b>第一条的 value 非空</b>，不是「有没有这个键」。
	 * 用 {@code @ConditionalOnProperty} 判键存在会踩 P2.3-B 那个坑：
	 * 显式配成空会被判成「有」，与真正的取值逻辑给出<b>不同答案</b>，
	 * 而这两边不一致的后果是两条 any-request 链同时注册 ⇒ 启动直接失败。
	 */
	public static final String MULTI_NAMESPACE_CONDITION =
			"!'${" + NAMESPACES_PROPERTY + "[0].value:}'.isBlank()";

	/** 与 {@link #MULTI_NAMESPACE_CONDITION} 相反的条件：单命名空间（多进程）形态。 */
	public static final String SINGLE_NAMESPACE_CONDITION =
			"'${" + NAMESPACES_PROPERTY + "[0].value:}'.isBlank()";

	private PathPrefixConstants() {
	}

	/** 本服务是否做了前缀改造。 */
	public static boolean hasPrefix(String prefix) {
		return prefix != null && !prefix.isBlank();
	}

	/** 去空白、补前导斜杠、去尾部斜杠：{@code pay} / {@code /pay/} 与 {@code /pay} 必须是同一个前缀。 */
	public static String normalize(String prefix) {
		if (!hasPrefix(prefix)) {
			return "";
		}
		String value = prefix.trim();
		if (!value.startsWith("/")) {
			value = "/" + value;
		}
		while (value.length() > 1 && value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}

	/**
	 * 给一组**本服务 controller 的**路径字面量补前缀。前缀为空时是恒等变换。
	 *
	 * <p>⚠️ 只能用在「确实会被 {@code addPathPrefix} 改写」的路径上。
	 * {@code /actuator/**}、{@code /error}、{@code /v3/api-docs/**}、{@code /swagger-ui/**}
	 * 这些不是本服务 controller，加前缀反而会让它们失配 —— 判据是
	 * {@code addPathPrefix} 的包名断言：只有 {@code base-package} 之下的类会被改写。
	 */
	public static List<String> prefixed(String prefix, List<String> urls) {
		String value = normalize(prefix);
		if (value.isEmpty()) {
			return List.copyOf(urls);
		}
		List<String> result = new ArrayList<>(urls.size());
		for (String url : urls) {
			result.add(url.startsWith("/") ? value + url : value + "/" + url);
		}
		return List.copyOf(result);
	}
}
