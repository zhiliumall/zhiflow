package com.zhiflow.common.security.component;

import tools.jackson.databind.ObjectMapper;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.observability.ErrorCode;
import com.zhiflow.common.core.util.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.PrintWriter;

/**
 * @author 辰
 * @date 2023年10月26日14:51:07
 * 客户端异常处理
 * 根据 AuthenticationException不同细化异常处理
 */
@RequiredArgsConstructor
public class ResourceAuthExceptionEntryPoint implements AuthenticationEntryPoint {
	private final ObjectMapper objectMapper;

	private final MessageSource messageSource;

	@Override
	@SneakyThrows
	public void commence(HttpServletRequest request, HttpServletResponse response,
						 AuthenticationException authException) {
		response.setCharacterEncoding(CommonConstants.UTF8);
		response.setContentType(CommonConstants.CONTENT_TYPE);
		R<String> result = new R<>();
		result.setCode(CommonConstants.FAIL);
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		String errorCode = ErrorCode.BIZ_AUTH_UNAUTHORIZED;
		if (authException != null) {
			result.setMsg("error");
			result.setData(authException.getMessage());
		}

		// 针对令牌过期返回特殊的 424
		if (authException instanceof InvalidBearerTokenException
				|| authException instanceof InsufficientAuthenticationException) {
			response.setStatus(org.springframework.http.HttpStatus.FAILED_DEPENDENCY.value());
			result.setMsg(this.messageSource.getMessage("OAuth2ResourceOwnerBaseAuthenticationProvider.tokenExpired",
					null, LocaleContextHolder.getLocale()));
			errorCode = ErrorCode.BIZ_AUTH_TOKEN_INVALID;
		}
		// 网关据此把 401/424 记成业务拒绝而不是服务故障；MDC 让本请求随后的日志行带上它
		response.setHeader(ErrorCode.HEADER, errorCode);
		MDC.put(ErrorCode.MDC_KEY, errorCode);
		PrintWriter printWriter = response.getWriter();
		printWriter.append(objectMapper.writeValueAsString(result));
	}

}
