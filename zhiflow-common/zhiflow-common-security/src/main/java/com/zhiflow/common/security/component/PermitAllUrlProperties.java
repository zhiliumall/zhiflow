package com.zhiflow.common.security.component;

import cn.hutool.core.util.ReUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.zhiflow.common.core.constant.PathPrefixConstants;
import com.zhiflow.common.core.constant.PublicUrlConstants;
import com.zhiflow.common.security.annotation.Inside;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * @author 辰
 * @date 2023年10月26日17:09:28
 * <p>
 * 资源服务器对外直接暴露URL,如果设置contex-path 要特殊处理
 */
@Slf4j
@Component("permitAllUrlProperties")
public class PermitAllUrlProperties implements InitializingBean {
	private static final Pattern PATTERN = Pattern.compile("\\{(.*?)\\}");
	private static final String API_LOGIN_CLASS_NAME = "com.zhiflow.common.portal.annotation.ApiLogin";

	private static final String[] DEFAULT_IGNORE_URLS = new String[] { "/actuator/**", "/error", "/v3/api-docs" };

	@Getter
	@Setter
	private List<String> releaseUrls = new ArrayList<>();

	/**
	 * 本服务命名空间的认证机制。{@code api-login} 时额外装载 H5 门户的公开路径
	 * （{@link PublicUrlConstants#PORTAL_PUBLIC_URLS}，含各支付回调）。
	 */
	@Value(SecurityScope.AUTHENTICATION_MODE_PLACEHOLDER)
	private String authenticationMode = SecurityScope.MODE_OAUTH2;

	/**
	 * 单体（多命名空间）形态的判据：{@code zhiflow.path-prefix.namespaces[0].value} 非空。
	 *
	 * <p>用 {@code @Value} 而不是注入 {@code SecurityNamespaceProperties}：本类是
	 * {@code InitializingBean}，在容器早期就 {@code afterPropertiesSet}，
	 * 注入另一个 bean 会把它的初始化顺序也提前 —— 本仓已经因为「急切注入提前依赖链」
	 * 引爆过既有循环引用（2026-08-16 upms 起不来并自动回滚两次）。
	 */
	@Value("${" + PathPrefixConstants.NAMESPACES_PROPERTY + "[0].value:}")
	private String firstNamespace = "";

	/** 本服务的内部路径前缀；写死的业务路径白名单要跟着它走。 */
	@Value(PathPrefixConstants.PLACEHOLDER)
	private String pathPrefix = "";

	private void addPatternsFromAnnotation(Object annotated, boolean isClass, HandlerMethod handlerMethod) {
		RequestMapping mapping = isClass
				? AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequestMapping.class)
				: AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequestMapping.class);
		if (mapping != null) {
			Stream.of(mapping.path(), mapping.value())
					.flatMap(Arrays::stream)
					.filter(p -> !p.isEmpty())
					.forEach(url -> releaseUrls.add(ReUtil.replaceAll(url, PATTERN, "*")));
		}
	}

	private boolean isApiLoginAnnotated(HandlerMethod handlerMethod) {
		for (Annotation annotation : handlerMethod.getMethod().getAnnotations()) {
			if (API_LOGIN_CLASS_NAME.equals(annotation.annotationType().getName())) {
				return true;
			}
		}
		for (Annotation annotation : handlerMethod.getBeanType().getAnnotations()) {
			if (API_LOGIN_CLASS_NAME.equals(annotation.annotationType().getName())) {
				return true;
			}
		}
		return false;
	}

	private void addConditionPatterns(PathPatternsRequestCondition condition) {
		condition.getPatternValues().forEach(url -> releaseUrls.add(ReUtil.replaceAll(url, PATTERN, "*")));
	}

	@Override
	public void afterPropertiesSet() {
		// 先留一份配置里的**字面量**原文，下面的自检要拿它逐条比对前缀。
		// 必须在任何 addAll 之前取，之后这个列表里会混进 DEFAULT/COMMON 与 handler 现算的路径。
		List<String> configuredLiterals = List.copyOf(releaseUrls);
		releaseUrls = new ArrayList<>(releaseUrls);
		releaseUrls.addAll(Arrays.asList(DEFAULT_IGNORE_URLS));
		// 所有服务共享的基础设施级公开路径（Swagger、静态资源、验证码等）。
		releaseUrls.addAll(PublicUrlConstants.COMMON_PUBLIC_URLS);
		// PORTAL_PUBLIC_URLS（H5 端业务路径，如 /captcha/**）仅在 H5 portal 服务（zhiflow-mall-api）
		// 上装载。在其他服务（upms-admin、mall-admin、auth、gateway 等）共享同一份白名单
		// 会导致同名 admin 接口被误判为公开 → BaseBearerTokenExtractor 跳过 token 抽取
		// → SecurityContext 匿名 → @PreAuthorize 拒绝访问。
		// 该列表同时被 BaseResourceServerConfiguration 用作 permitAll() 白名单，
		// 因此从中添加/移除条目会直接影响是否强制要求 Bearer token。
		//
		// [P2.3-B] 判据从 `"zhiflow-mall-api".equals(spring.application.name)` 换成显式声明的
		// 认证机制（见 SecurityScope）。共享库里写死某个服务的名字，在改名 / 拆服务 /
		// 阶段 3 合并进单体时会让这份白名单静默换一套，而编译期与日志都发现不了。
		//
		// ⚠️ PORTAL_PUBLIC_URLS 全是 mall-api 自己的 controller ⇒ 打开内部路径前缀之后
		// 真实路径会多一段，这张写死的常量表必须跟着走（COMMON/DEFAULT 那两批不是 controller，
		// addPathPrefix 不会改写它们，加前缀反而失配）。releaseUrls 的大头是从 handler 映射
		// 现算的、自动跟着前缀走，只有这类字面量需要手工对齐。
		if (SecurityScope.isApiLoginMode(this.authenticationMode)) {
			releaseUrls.addAll(PathPrefixConstants.prefixed(this.pathPrefix, PublicUrlConstants.PORTAL_PUBLIC_URLS));
		}
		RequestMappingHandlerMapping mapping = SpringUtil.getBean("requestMappingHandlerMapping");
		Map<RequestMappingInfo, HandlerMethod> map = mapping.getHandlerMethods();

		map.keySet().forEach(info -> {
			HandlerMethod handlerMethod = map.get(info);

			Inside methodInside = AnnotationUtils.findAnnotation(handlerMethod.getMethod(), Inside.class);
			Inside classInside = AnnotationUtils.findAnnotation(handlerMethod.getBeanType(), Inside.class);
			boolean hasApiLogin = isApiLoginAnnotated(handlerMethod);

			PathPatternsRequestCondition condition = info.getPathPatternsCondition();
			if (condition != null) {
				if (methodInside != null || classInside != null || hasApiLogin) {
					addConditionPatterns(condition);
				}
			} else {
				if (methodInside != null) {
					addPatternsFromAnnotation(methodInside, false, handlerMethod);
				}
				if (classInside != null) {
					addPatternsFromAnnotation(classInside, true, handlerMethod);
				}
				if (hasApiLogin) {
					addPatternsFromAnnotation(handlerMethod.getMethod(), false, handlerMethod);
				}
			}
		});
		releaseUrls = releaseUrls.stream().distinct().toList();

		// ⛔ 单体形态下 `/**` 一律拒绝启动。
		//
		// 2026-08-16 实测：`security.oauth2.client.release-urls` 在 **7 份 data-id 里各有一份**，
		// 而 spring.config.import 是后者覆盖前者 ⇒ 单体里只有 import 列表最后那个服务的那份生效。
		// 实测生效的是 mall-api 的 `- /**` —— 对 mall-api 自己完全合理（它是 C 端门户、
		// api-login 模式，Security 层本来就全放行），但在单体里它吃掉了其余 6 份白名单，
		// oauth2 命名空间链于是变成 requestMatchers("/**").permitAll()：
		// **mall / upms / pay / live / weixin 的 web 层鉴权全部消失**，
		// 只剩方法级 @PreAuthorize 侥幸兜住一部分，没有 @PreAuthorize 的后台端点直接匿名可达。
		//
		// 这一类错误的可怕之处在于**完全静默**：进程正常起、接口正常返回、日志一行不报。
		// 所以必须 fail-fast —— 单体里 `/**` 没有任何正确语义，
		// 「本服务全放行」在单体里的正确写法是该命名空间的前缀（如 /mallapi/**），
		// 而 api-login 命名空间根本不经过这份白名单。
		if (PathPrefixConstants.hasPrefix(this.firstNamespace) && releaseUrls.contains("/**")) {
			throw new IllegalStateException("""
					单体（多命名空间）形态下 security.oauth2.client.release-urls 含 "/**"，拒绝启动。
					它会让所有 oauth2 命名空间的 web 层鉴权**静默**全放行（实测来源：mall-api 的那份，
					它排在 spring.config.import 最后，覆盖了其余 6 份）。
					修法：在单体自己的 data-id（import 列表最末）里显式声明一份合并后的 release-urls，
					逐条带上各自的命名空间前缀；「本服务全放行」在单体里不成立。
					当前白名单共 %d 条。""".formatted(releaseUrls.size()));
		}

		// 启动即自述。这份白名单同时决定「哪些路径不要 Bearer token」与
		// 「BaseBearerTokenExtractor 跳不跳过 token 抽取」，算错了的表现是**鉴权静默消失**，
		// 而它是运行期从全部 handler 映射现算的 —— 没有任何静态手段能看到它算成了什么。
		// 单体形态下它一次性扫 8 个服务的映射，规模与内容都与单服务时完全不同，
		// 更需要有个能直接看的出口（2026-08-16 定位「/mall/viptype/list 为何被放行」时，
		// 没有这行日志就只能靠改代码加打印）。
		log.info("[permit-all] 白名单 {} 条（认证机制={}，前缀={}）", releaseUrls.size(),
				this.authenticationMode, this.pathPrefix);
		if (log.isDebugEnabled()) {
			log.debug("[permit-all] 明细：{}", releaseUrls);
		}
		warnOnUnprefixedLiterals(configuredLiterals);
	}

	/**
	 * 不是 controller、因而**不会**被 {@code addPathPrefix} 改写的路径前缀。
	 * 这些条目在配置里就该写成无前缀的原样，加了前缀反而失配。
	 *
	 * <p>判据取自 2026-08-17 的逐条实测（容器直连，绕过网关的 PrefixPath）：
	 * {@code /actuator/health} 与 {@code /v3/api-docs} 返回 200、{@code /ws/x} 返回 404
	 * （404 = 过了安全层、只是没有映射，说明白名单命中了），三者都是无前缀生效。
	 */
	private static final List<String> INFRA_LITERAL_PREFIXES = List.of(
			"/actuator", "/v3/api-docs", "/error", "/ws", "/webjars", "/swagger",
			"/favicon", "/css", "/js", "/doc.html", "/druid", "/token", "/oauth2", "/.well-known");

	/**
	 * 启动自检：{@code security.oauth2.client.release-urls} 里任何一条既不是基础设施路径、
	 * 又不以本服务的 {@code zhiflow.path-prefix.value} 开头的字面量，都打 WARN。
	 *
	 * <p><b>为什么需要它</b>：P2.3 打开内部路径前缀后，controller 的真实路径统统多了一段
	 * （{@code /shopapply/addedit} → {@code /mall/shopapply/addedit}），而配置里这份白名单是
	 * <b>字面量原样使用</b>的（只有 api-login 模式的 {@code PORTAL_PUBLIC_URLS} 走了
	 * {@link PathPrefixConstants#prefixed}）。于是整份白名单一条都匹配不上，
	 * 「本该匿名的接口」全部变成要 token —— 而且**完全静默**：进程正常起、日志一行不报，
	 * 直到有人去调才发现 424。2026-08-17 实测 mall-admin/upms-admin/pay/weixin 四份配置里
	 * 27 条业务白名单有 24 条就是这么死掉的。
	 *
	 * <p><b>为什么是 WARN 而不是 fail-fast</b>：这份配置由运维改，
	 * 一条写错就让服务起不来的代价远大于收益；而且「白名单条目失配」的后果是**更严格**
	 * （多要一个 token）而非更宽松，不属于必须拦在启动前的安全回归。
	 * 上面 {@code "/**"} 那条 fail-fast 才是反过来的：它会让鉴权静默消失。
	 */
	private void warnOnUnprefixedLiterals(List<String> configuredLiterals) {
		List<String> suspicious = findUnprefixedLiterals(configuredLiterals, this.pathPrefix);
		if (!suspicious.isEmpty()) {
			log.warn("""
					[permit-all] ⚠ release-urls 里有 {} 条业务路径**没有带本服务前缀 {}**，它们一条都匹配不上，
					对应接口实际仍要 Bearer token（表现为 424），且不会有任何报错。
					失配清单：{}
					修法：确认这些接口是否仍需匿名 —— 需要就在配置里补上前缀（如 {}{}），
					不需要就从 release-urls 删掉并留一行注释说明原因。""",
					suspicious.size(), this.pathPrefix, suspicious,
					this.pathPrefix, suspicious.getFirst());
		}
	}

	/**
	 * 挑出「既不是基础设施路径、又没带本服务前缀」的字面量。抽成静态方法只为可单测 ——
	 * 这条判据一旦写错，要么天天刷无意义 WARN、要么继续放过真正的失配。
	 *
	 * @param pathPrefix 本服务的内部路径前缀；为空表示该服务没开前缀（如 im-server），此时无从判断，返回空
	 */
	static List<String> findUnprefixedLiterals(List<String> configuredLiterals, String pathPrefix) {
		if (!PathPrefixConstants.hasPrefix(pathPrefix) || configuredLiterals == null) {
			return List.of();
		}
		return configuredLiterals.stream()
				.filter(url -> url != null && url.startsWith("/"))
				.filter(url -> INFRA_LITERAL_PREFIXES.stream().noneMatch(url::startsWith))
				.filter(url -> !url.startsWith(pathPrefix + "/") && !url.equals(pathPrefix))
				.toList();
	}
}
