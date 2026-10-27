package com.zhiflow.common.security.internal;

import com.zhiflow.common.core.constant.SecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 让**进程内**的契约本地实现，在被调方眼里与今天的 HTTP 调用长得一样。
 *
 * <h2>要解决的问题</h2>
 * <p>今天服务间调用靠一个请求头区分内外：网关 {@code BaseRequestGlobalFilter} 对每个外部请求
 * <b>删掉 {@code from} 头</b>，而契约客户端的 {@code InternalCallInterceptor} 自动加上
 * {@code from=Y}；{@link com.zhiflow.common.security.component.BaseSecurityInsideAspect}
 * 注入 {@code HttpServletRequest} 读这个头，{@code @Inside(value=true)} 的端点头不对就 403。
 *
 * <p>阶段 3 把服务装进同一个进程后，本地实现直接调 controller 方法时，
 * 切面注入的那个 request 代理解析到的是<b>外层那个正在处理的 C 端请求</b> ——
 * 它的 {@code from} 早被网关剥掉了 ⇒ <b>142 个 {@code @Inside} 端点会全线 403</b>。
 *
 * <h2>为什么是「换请求上下文」而不是「改切面判据」</h2>
 * <p>曾考虑让切面改读方法入参（契约<b>客户端</b>接口上 110/124 个方法确实声明了
 * {@code @RequestHeader(FROM)}）。但实测 <b>142 个 {@code @Inside} controller 方法里只有 2 个
 * 声明了该入参</b> —— 客户端把它当 HTTP 头发出去，服务端根本不需要声明。
 * 那条路要么退化成现状，要么得改 140 处安全端点的签名。
 *
 * <p>所以改成在调用前后临时替换 {@link RequestContextHolder} 的请求上下文：
 * <b>安全切面一行不改、140 个 controller 一行不改</b>，判据仍然是「请求头里的 from」。
 *
 * <h2>被调方看到的头：刻意只有两个</h2>
 * <p>HTTP 路径下被调方收到的是一个<b>全新的请求</b>，只带契约客户端发出的东西
 * （{@code InternalCallInterceptor} 的 {@code from} + {@code tenantRequestInterceptor} 的租户头
 * + content 协商头），<b>没有调用方那个 C 端请求的任何东西</b>。
 * 所以这里刻意<b>不</b>包装外层请求去做委托 —— 委托会让被调方看见 {@code third-session}、
 * {@code client-type} 这些今天它看不到的头，那是<b>比现状更宽</b>的语义，属于静默变化。
 *
 * <h2>⚠️ 唯一的风险是没还原</h2>
 * <p>上下文没还原 = 后续代码顶着「内部调用」身份跑 = {@code @Inside} 形同虚设。
 * 所以：① 只暴露 {@code call/run} 两个高阶方法，不暴露 set/clear；
 * ② 还原写在 {@code finally}，异常路径同样还原；③ 嵌套调用逐层还原到进入前的那一个；
 * ④ 单测覆盖正常/异常/嵌套/无外层请求四种路径。
 */
@Slf4j
public final class InternalCallContext {

	private static final ThreadLocal<String> OPERATION_ID = new ThreadLocal<>();

	private InternalCallContext() {
	}

	/** 以「内部调用」身份执行并返回结果。 */
	public static <T> T call(Supplier<T> action) {
		RequestAttributes previous = RequestContextHolder.getRequestAttributes();
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(internalRequest(previous)));
		try {
			return action.get();
		}
		finally {
			// 还原成进入前的那一个（可能是 null，也可能是外层的 C 端请求）——
			// 嵌套调用因此逐层还原，不会把最内层的内部身份留给外层。
			RequestContextHolder.setRequestAttributes(previous);
		}
	}

	/**
	 * 以内部调用身份执行，并把稳定的命令 operationId 传给业务门面。
	 *
	 * <p>operationId 是可选的：历史 HTTP、本地调用和查询调用仍可使用无参 overload。
	 * 与请求上下文一样，这里必须支持嵌套和异常路径逐层还原，不能把外层命令号留给后续调用。
	 */
	public static <T> T call(String operationId, Supplier<T> action) {
		String previous = OPERATION_ID.get();
		if (operationId == null || operationId.isBlank()) {
			OPERATION_ID.remove();
		}
		else {
			OPERATION_ID.set(operationId);
		}
		try {
			return call(action);
		}
		finally {
			if (previous == null) {
				OPERATION_ID.remove();
			}
			else {
				OPERATION_ID.set(previous);
			}
		}
	}

	/** 无返回值版本。 */
	public static void run(Runnable action) {
		call(() -> {
			action.run();
			return null;
		});
	}

	/** 无返回值版本，带 operationId。 */
	public static void run(String operationId, Runnable action) {
		call(operationId, () -> {
			action.run();
			return null;
		});
	}

	/** 当前内部调用的稳定 operationId；没有命令上下文时返回 {@code null}。 */
	public static String currentOperationId() {
		return OPERATION_ID.get();
	}

	/**
	 * 造一个只带 {@code from} 与租户头的请求。租户头从外层请求透传 ——
	 * 与 HTTP 路径下 {@code tenantRequestInterceptor} 干的事一致。
	 */
	private static HttpServletRequest internalRequest(RequestAttributes previous) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put(SecurityConstants.FROM, SecurityConstants.FROM_IN);
		if (previous instanceof ServletRequestAttributes servlet) {
			String tenantId = servlet.getRequest().getHeader(TENANT_HEADER);
			if (tenantId != null && !tenantId.isBlank()) {
				headers.put(TENANT_HEADER, tenantId);
			}
		}
		return newRequest(headers);
	}

	/** 与 {@code TenantContextHolder} / 网关口径一致的租户头名。 */
	private static final String TENANT_HEADER = "tenant-id";

	/**
	 * 一个只回答「头」相关问题的最小 {@code HttpServletRequest}。
	 *
	 * <p>用动态代理而不是实现整个接口（60+ 方法）：实现全套的代价是每次 servlet API 升级
	 * 都要跟着改，而这里真正被读的只有三个取头的方法。其余方法一律返回类型默认值 ——
	 * <b>宁可返回 null/0/false，也不要抛异常</b>：抛异常会把一次本该成功的内部调用
	 * 变成一个难以定位的 500。
	 */
	private static HttpServletRequest newRequest(Map<String, String> headers) {
		InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
			case "getHeader" -> headers.get(String.valueOf(args[0]));
			case "getHeaders" -> {
				String value = headers.get(String.valueOf(args[0]));
				yield value == null ? Collections.emptyEnumeration()
						: Collections.enumeration(Collections.singletonList(value));
			}
			case "getHeaderNames" -> Collections.enumeration(headers.keySet());
			case "getAttribute", "getCharacterEncoding", "getContentType", "getRequestURI", "getMethod",
					"getContextPath", "getServletPath", "getPathInfo", "getQueryString", "getRemoteAddr" ->
					null;
			case "getAttributeNames", "getParameterNames" -> Collections.emptyEnumeration();
			case "getParameterMap" -> Collections.emptyMap();
			case "toString" -> "InternalCallRequest" + headers;
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals" -> proxy == args[0];
			default -> defaultValue(method.getReturnType());
		};
		return (HttpServletRequest) Proxy.newProxyInstance(InternalCallContext.class.getClassLoader(),
				new Class<?>[] { HttpServletRequest.class }, handler);
	}

	private static Object defaultValue(Class<?> type) {
		if (!type.isPrimitive()) {
			return null;
		}
		if (type == boolean.class) {
			return Boolean.FALSE;
		}
		if (type == void.class) {
			return null;
		}
		if (type == long.class) {
			return 0L;
		}
		if (type == int.class || type == short.class || type == byte.class || type == char.class) {
			return 0;
		}
		return type == double.class ? 0d : 0f;
	}
}
