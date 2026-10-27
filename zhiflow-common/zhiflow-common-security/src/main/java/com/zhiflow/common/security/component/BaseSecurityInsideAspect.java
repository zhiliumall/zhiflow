package com.zhiflow.common.security.component;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.security.annotation.Inside;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;


/**
 * @author 辰
 * @date 2023年10月26日17:07:50
 * <p>
 * 服务间接口不鉴权处理逻辑
 *
 * <p><b>2026-08-15：补 {@code @Component}。此前这个切面从未生效过。</b>
 * {@code @Aspect} 不是 Spring 的 stereotype 注解，光有它不会被 component-scan 拾取；
 * 而本类既没有 {@code @Component}，也不在任何 {@code AutoConfiguration.imports} 里，
 * 全仓也没有任何 {@code @Bean} 方法造它（{@code @Component} 的 import 语句倒是一直在，
 * 只是没被用上）。后果是下面那段 {@code from} 头校验的 advice **一次都没执行过**：
 * <ul>
 *   <li>{@code PermitAllUrlProperties} 把带 {@code @Inside} 的端点无条件放进 permitAll
 *       白名单，Spring Security 层不要 Bearer token；</li>
 *   <li>本该在后面补上「必须带 {@code from: Y}」的这一道，从来不存在；</li>
 *   <li>于是全部 {@code @Inside(value=true)} 端点对公网无鉴权可达。2026-08-14 实测：
 *       未认证 {@code GET /upms/tenant/inside/list} 返回 200
 *       与全量租户列表。</li>
 * </ul>
 *
 * <p><b>为什么补上它不会打断服务间调用</b>：契约客户端不经网关（{@code HttpInterfaceClientFactory}
 * 用 {@code http://<服务名>} 经 LoadBalancer 直连实例），且 {@code InternalCallInterceptor}
 * 会无条件补 {@code from: Y}；而外部请求经网关时 {@code BaseRequestGlobalFilter} 会把
 * {@code from} 头删掉，正好落进下面的拒绝分支。
 *
 * <p>{@code value=false} 的端点（如免登录品牌接口、微信外部回调）不受影响 ——
 * 下面的判断只在 {@code inside.value()} 为真时才校验。
 */
@Slf4j
@Aspect
@Component
@AllArgsConstructor
public class BaseSecurityInsideAspect  implements Ordered {
	private final HttpServletRequest request;


	@SneakyThrows
	@Before("@within(inside) || @annotation(inside)")
	public void around(JoinPoint point, Inside inside) {
		// 实际注入的inside实体由表达式后一个注解决定，即是方法上的@Inside注解实体，若方法上无@Inside注解，则获取类上的
		if (inside == null) {
			Class<?> clazz = point.getTarget().getClass();
			inside = AnnotationUtils.findAnnotation(clazz, Inside.class);
		}
		String header = request.getHeader(SecurityConstants.FROM);
		if (inside.value() && !StrUtil.equals(SecurityConstants.FROM_IN, header)) {
			log.warn("访问接口 {} 没有权限", point.getSignature().getName());
			throw new AccessDeniedException("访问被拒绝，没有权限");
		}
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 1;
	}
}
