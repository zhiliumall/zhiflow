package com.zhiflow.common.core.constant;

/**
 * 单体（形态一 {@code all}）里<b>一个服务的命名空间</b>：前缀 + controller 包 + 认证机制。
 *
 * <h2>为什么这个结构要放在 common-core</h2>
 * <p>它被<b>两个互不依赖的模块</b>读：
 * <ul>
 * <li>common-data 的 {@code PathPrefixProperties} —— 拿它给 controller 挂 MVC 前缀；</li>
 * <li>common-security 的 {@code SecurityNamespaceProperties} —— 拿它圈安全链管辖范围。</li>
 * </ul>
 * common-security <b>不依赖</b> common-data（实测 pom 里没有），所以两边只能各自绑定同一段配置。
 * 结构类若也各写一份，迟早写岔 —— 而写岔的表现是
 * 「MVC 加了前缀、安全链却还按老路径圈范围」，正是 2026-08-15 auth 那次
 * 12 个端点全变 302、登出失效的形态。所以结构下沉到两边都依赖的 common-core，只此一份。
 *
 * <p>这与 {@link PathPrefixConstants} 把属性名字符串收在一处是同一条纪律。
 */
public class PathNamespace {

	/** 内部前缀，如 {@code /mallapi}。 */
	private String value = "";

	/**
	 * 该命名空间的 controller 包前缀。
	 *
	 * <p><b>留空是有明确语义的</b>：表示「本命名空间不由 {@code addPathPrefix} 加前缀」。
	 * 唯一用例是 im-server —— 它的 {@code /api} 是<b>写在 controller 源码里</b>的
	 * （47 个 {@code @RequestMapping("/api/...")}），再加一层会变成 {@code /api/api/im/**}。
	 * 这种条目仍然参与安全链圈范围，只是不参与 MVC 加前缀。
	 */
	private String basePackage = "";

	/**
	 * 该命名空间由哪套认证机制管辖：{@code oauth2}（默认）或 {@code api-login}。
	 *
	 * <p>留空按 {@code oauth2} 处理 —— 漏配时退到<b>更严</b>的一边。
	 * 反过来默认成放行的话，任何一次漏配都是一次静默的鉴权全失效。
	 */
	private String authenticationMode = "";

	public String getValue() {
		return this.value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	public String getBasePackage() {
		return this.basePackage;
	}

	public void setBasePackage(String basePackage) {
		this.basePackage = basePackage;
	}

	public String getAuthenticationMode() {
		return this.authenticationMode;
	}

	public void setAuthenticationMode(String authenticationMode) {
		this.authenticationMode = authenticationMode;
	}

	/** 本条目是否有效（有前缀）。空壳条目在两侧都应当被跳过，而不是被当成「匹配一切」。 */
	public boolean hasValue() {
		return PathPrefixConstants.hasPrefix(this.value);
	}

	/** 归一化后的前缀，口径与 MVC 加前缀、白名单补前缀完全一致。 */
	public String normalizedValue() {
		return PathPrefixConstants.normalize(this.value);
	}

	/** 是否由 {@code addPathPrefix} 加前缀（{@code basePackage} 留空的条目不是，如 im-server）。 */
	public boolean addsMvcPrefix() {
		return hasValue() && this.basePackage != null && !this.basePackage.isBlank();
	}

	@Override
	public String toString() {
		return this.value + "→" + (this.basePackage.isBlank() ? "(源码内前缀)" : this.basePackage)
				+ (this.authenticationMode.isBlank() ? "" : "[" + this.authenticationMode + "]");
	}
}
