package com.zhiflow.boot.edge;

import com.zhiflow.common.core.constant.SecurityConstants;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 网关 {@code BaseRequestGlobalFilter} 里 {@code from} 头剥离的 servlet 版。
 *
 * <h2>为什么这是四件移植里最不能少的一件</h2>
 * <p>{@code @Inside} 边界（142 个端点）的判据是「请求头 {@code from} 是否等于
 * {@code Y}」（{@code BaseSecurityInsideAspect}）。这个判据的<b>唯一</b>不可伪造性来源，
 * 是「外部请求的 {@code from} 头在进业务代码之前一定被剥掉」—— 多进程形态由网关的
 * {@code BaseRequestGlobalFilter}（order=-1000，早于一切路由转发）承担。
 * 去掉网关、boot 端口直接对外之后，没有这个 filter 就等于任何外部请求带上
 * {@code from: Y} 即可打穿全部 {@code @Inside} 端点（IDOR 先例见
 * {@code /mallapi/order/refund/eligibility}）。
 *
 * <h2>与 {@code InternalCallContext} 为什么不冲突（读码证实，非推测）</h2>
 * <p>单体的进程内内部调用靠 {@code InternalCallContext.call/run} 获得「内部身份」。
 * 它的机制是<b>临时替换 {@code RequestContextHolder} 的 RequestAttributes</b>：
 * 造一个只带 {@code from=Y} 与租户头的合成 {@code HttpServletRequest}（动态代理，
 * 见 {@code InternalCallContext#newRequest}），包成 {@code ServletRequestAttributes}
 * 塞进 {@code RequestContextHolder}，调用结束在 {@code finally} 里还原。
 * 这个合成请求<b>从不经过 servlet 过滤器链</b> —— 它不是容器分发的请求。
 *
 * <p>而校验方 {@code BaseSecurityInsideAspect} 注入的 {@code HttpServletRequest}
 * 是 Spring 的 objectFactory 代理，每次取头都<b>经 {@code RequestContextHolder} 现解析</b>：
 * <ul>
 * <li>外部请求：{@code RequestContextFilter}/{@code DispatcherServlet} 用<b>传进它们的
 *     那个请求对象</b>填 {@code RequestContextHolder}，而本 filter 早于它们执行、
 *     向下游传的是包装后的请求 ⇒ 切面看到的 {@code from} 已被剥掉；</li>
 * <li>内部调用：{@code InternalCallContext} 已把 {@code RequestContextHolder} 换成
 *     合成请求 ⇒ 切面看到 {@code from=Y}，与本 filter 无交集。</li>
 * </ul>
 * 结论：本 filter 只见「容器分发的外部请求」，剥不掉（也摸不到）内部身份。
 *
 * <h2>为什么是包装而不是留痕</h2>
 * <p>servlet 请求头不可写，只能包装隐藏。剥离对大小写不敏感（HTTP 头本身大小写
 * 不敏感，网关侧 {@code HttpHeaders.remove} 也是大小写不敏感的）。
 * {@code getHeaderNames()/getHeaders()/getDateHeader()/getIntHeader()} 一并遮蔽，
 * 不给「换个取法就读到」留缝。
 *
 * <p>注册条件与顺序见 {@link GatewayParityConfiguration}：必须最早执行
 * （{@code Ordered.HIGHEST_PRECEDENCE}），早于租户过滤器（MIN_VALUE+50）、
 * Spring Security（-100）与 MVC。
 *
 * @see GatewayParityConfiguration
 */
@Slf4j
public class FromHeaderStrippingFilter implements Filter {

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (request instanceof HttpServletRequest http && http.getHeader(SecurityConstants.FROM) != null) {
			// 外部请求带 from 头 = 疑似伪造内部身份（合法内部调用不走 servlet 链）。留一行痕。
			log.warn("[gateway-parity] 剥离外部请求的 from 头: {} {}", http.getMethod(), http.getRequestURI());
			chain.doFilter(new FromStrippedRequest(http), response);
			return;
		}
		chain.doFilter(request, response);
	}

	/** 对下游完全隐藏 {@code from} 头（大小写不敏感），其余头原样透传。 */
	static final class FromStrippedRequest extends HttpServletRequestWrapper {

		FromStrippedRequest(HttpServletRequest request) {
			super(request);
		}

		private static boolean isFrom(String name) {
			return SecurityConstants.FROM.equalsIgnoreCase(name);
		}

		@Override
		public String getHeader(String name) {
			return isFrom(name) ? null : super.getHeader(name);
		}

		@Override
		public Enumeration<String> getHeaders(String name) {
			return isFrom(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
		}

		@Override
		public Enumeration<String> getHeaderNames() {
			List<String> names = Collections.list(super.getHeaderNames()).stream()
				.filter(name -> !isFrom(name))
				.toList();
			return Collections.enumeration(names);
		}

		@Override
		public long getDateHeader(String name) {
			return isFrom(name) ? -1 : super.getDateHeader(name);
		}

		@Override
		public int getIntHeader(String name) {
			return isFrom(name) ? -1 : super.getIntHeader(name);
		}

	}

}
