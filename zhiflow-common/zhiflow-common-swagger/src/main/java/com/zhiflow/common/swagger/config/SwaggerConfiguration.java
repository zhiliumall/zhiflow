package com.zhiflow.common.swagger.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

/**
 * 参考地址：https://blog.csdn.net/Blueeyedboy521/article/details/125838784
 * swagger配置
 *
 */
@Configuration
@RequiredArgsConstructor
public class SwaggerConfiguration {

	private final SwaggerProperties swaggerProperties;

	@Bean
	public OpenAPI springOpenAPI() {
		OpenAPI openAPI = new OpenAPI().info(new Info()
				.title(swaggerProperties.getTitle())
				.description(swaggerProperties.getDescription())
				.version(swaggerProperties.getVersion())
		);
		// 接口全局添加 Authorization 参数
		openAPI.addSecurityItem(new SecurityRequirement().addList(HttpHeaders.AUTHORIZATION));
		openAPI.schemaRequirement(HttpHeaders.AUTHORIZATION, this.securityScheme());
		return openAPI;
	}

	// 全局添加token
	private SecurityScheme securityScheme() {
		SecurityScheme securityScheme = new SecurityScheme();
		//类型
		securityScheme.setType(SecurityScheme.Type.APIKEY);
		//请求头的name
		securityScheme.setName(HttpHeaders.AUTHORIZATION);
		//token所在未知
		securityScheme.setIn(SecurityScheme.In.HEADER);
		return securityScheme;
	}

}
