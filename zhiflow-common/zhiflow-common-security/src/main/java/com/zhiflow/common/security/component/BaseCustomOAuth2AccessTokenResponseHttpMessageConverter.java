package com.zhiflow.common.security.component;

import tools.jackson.databind.json.JsonMapper;
import com.zhiflow.common.core.util.SpringContextHolder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.core.ResolvableType;
import org.springframework.http.converter.SmartHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.oauth2.core.endpoint.DefaultOAuth2AccessTokenResponseMapConverter;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;

import java.util.Map;

/**
 * 扩展原生的实现，支持 Long2String
 *
 * @author 辰
 * @date 2023年10月26日17:15:47
 */
public class BaseCustomOAuth2AccessTokenResponseHttpMessageConverter
		extends OAuth2AccessTokenResponseHttpMessageConverter {

	private static final ParameterizedTypeReference<Map<String, Object>> STRING_OBJECT_MAP = new ParameterizedTypeReference<Map<String, Object>>() {
	};

	private Converter<OAuth2AccessTokenResponse, Map<String, Object>> accessTokenResponseParametersConverter = new DefaultOAuth2AccessTokenResponseMapConverter();

	@Override
	protected void writeInternal(OAuth2AccessTokenResponse tokenResponse, HttpOutputMessage outputMessage)
			throws HttpMessageNotWritableException {
		try {
			Map<String, Object> tokenResponseParameters = this.accessTokenResponseParametersConverter
				.convert(tokenResponse);

			// Jackson 3 迁移：Framework 7 里 Jackson 3 的 JSON 转换器是 JacksonJsonHttpMessageConverter，
			// 它要的是 JsonMapper（Boot 4 的 Jackson 自动配置注册的就是这个类型）。
			// 原来用的 MappingJackson2HttpMessageConverter 只接受 Jackson 2 的 ObjectMapper。
			JsonMapper jsonMapper = SpringContextHolder.getBean(JsonMapper.class);
			// Framework 7 的 Jackson 转换器实现的是新的 SmartHttpMessageConverter 接口：
			// 类型参数由 java.lang.reflect.Type 换成 ResolvableType，write 多一个 hints 参数。
			SmartHttpMessageConverter<Object> jsonMessageConverter = new JacksonJsonHttpMessageConverter(
					jsonMapper);
			jsonMessageConverter.write(tokenResponseParameters,
					ResolvableType.forType(STRING_OBJECT_MAP.getType()), MediaType.APPLICATION_JSON,
					outputMessage, null);
		}
		catch (Exception ex) {
			throw new HttpMessageNotWritableException(
					"An error occurred writing the OAuth 2.0 Access Token Response: " + ex.getMessage(), ex);
		}
	}

}
