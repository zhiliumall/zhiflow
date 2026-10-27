package com.zhiflow.common.security.component;

import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;


/**
 * @author 辰
 * @date 2023年10月26日14:33:44
 */
@RequiredArgsConstructor
@ComponentScan("com.zhiflow.common.security")
public class BaseResourceServerAutoConfiguration {

	/**
	 * 请求令牌的抽取逻辑
	 * @param urlProperties 对外暴露的接口列表
	 * @return BearerTokenExtractor
	 */
	@Bean
	public BaseBearerTokenExtractor baseBearerTokenExtractor(PermitAllUrlProperties urlProperties) {
		return new BaseBearerTokenExtractor(urlProperties);
	}

	/**
	 * 资源服务器异常处理
	 * @param objectMapper jackson 输出对象
	 * @param securityMessageSource 自定义国际化处理器
	 * @return ResourceAuthExceptionEntryPoint
	 */
	@Bean
	public ResourceAuthExceptionEntryPoint resourceAuthExceptionEntryPoint(ObjectMapper objectMapper,
																		   @Qualifier("securityMessageSource") MessageSource securityMessageSource) {
		return new ResourceAuthExceptionEntryPoint(objectMapper, securityMessageSource);
	}

	/**
	 * 资源服务器toke内省处理器
	 * @param authorizationService token 存储实现
	 * @param snapshotCache 压测优化 A2 的内省缓存；只有 {@code @EnableBaseResourceServer} 的服务才有
	 * （见 {@link AuthorizationSnapshotConfiguration}），auth 里为 null
	 * @return TokenIntrospector
	 */
	@Bean
	public OpaqueTokenIntrospector opaqueTokenIntrospector(OAuth2AuthorizationService authorizationService,
														   ObjectProvider<AuthorizationSnapshotCache> snapshotCache) {
		return new BaseCustomOpaqueTokenIntrospector(authorizationService, snapshotCache.getIfAvailable());
	}

}