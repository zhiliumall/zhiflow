package com.zhiflow.auth.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.HttpUtil;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.ClientIpResolver;
import com.zhiflow.common.core.util.IpUtils;
import com.zhiflow.common.core.util.MsgUtils;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.core.util.SpringContextHolder;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.log.event.SysLogEvent;
import com.zhiflow.common.log.util.SysLogUtils;
import com.zhiflow.upms.common.entity.SysLog;
import com.zhiflow.upms.common.dto.SysLogLoginPayload;
import com.zhiflow.api.upms.LogLoginApi;
import com.zhiflow.common.data.tenant.TenantAwareAsync;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * @author 辰
 * @date 2023年10月26日18:19:19
 */
@Slf4j
@AllArgsConstructor
public class BaseAuthenticationFailureEventHandler implements AuthenticationFailureHandler {

	/** OAuth2 错误码形态：invalid_grant、credentials_expired 这类小写下划线短标识。 */
	private static final Pattern OAUTH2_ERROR_CODE = Pattern.compile("[a-z][a-z_]{1,63}");

	/**
	 * 登录失败的错误体转换器（每次现建，不做成字段）。
	 *
	 * <p>原来是 {@code MappingJackson2HttpMessageConverter} —— 它自带一个**未经本项目配置的
	 * Jackson 2 ObjectMapper**，与成功分支（{@code BaseCustomOAuth2AccessTokenResponseHttpMessageConverter}，
	 * 用的是容器里的 Jackson 3 JsonMapper）是两套序列化口径。同一个接口成功/失败两条出口配置不一致，
	 * 正是「行为静默不一致」的种子，故一并统一到 Jackson 3。
	 *
	 * <p>不缓存成字段有两个原因：本 handler 在 SecurityFilterChain 装配阶段就被 new 出来，
	 * 那时 {@link SpringContextHolder} 还没拿到 ApplicationContext，字段初始化里取 bean 会让服务起不来；
	 * 而且类上有 {@code @AllArgsConstructor}，多一个实例字段就会改掉构造器签名。
	 * 登录失败本身是低频路径，每次现建的代价可以忽略。
	 */
	private static HttpMessageConverter<Object> errorConverter() {
		return new JacksonJsonHttpMessageConverter(SpringContextHolder.getBean(JsonMapper.class));
	}

	private final LogLoginApi logLoginApi;

	/**
	 * Called when an authentication attempt fails.
	 * @param request the request during which the authentication attempt occurred.
	 * @param response the response.
	 * @param exception the exception which was thrown to reject the authentication
	 * request.
	 */
	@Override
	@SneakyThrows
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
										AuthenticationException exception) {
		String username = request.getParameter(SecurityConstants.USERNAME);
		// sys_log_login.grant_type 是 NOT NULL，而失败请求未必带 grant_type
		// （监控探针每 30s 打一次 /auth/token/check_token，不带任何 OAuth 参数）。
		// 取不到就落 "unknown"：否则 MyBatis-Plus 跳过 null 字段 → 违反非空约束 →
		// 每次探测都在 upms 打一条 DataIntegrityViolationException 加整段栈，
		// 真实错误被埋掉（introspect 的 424 报错就曾被它埋住）。
		String grantType = StrUtil.blankToDefault(
				request.getParameter(OAuth2ParameterNames.GRANT_TYPE), "unknown");
		AuthEventMetrics.record(AuthEventMetrics.failureKind(request.getParameter(OAuth2ParameterNames.GRANT_TYPE)));
		OAuth2AuthenticationException exceptionOAuth2 = (OAuth2AuthenticationException)exception;
//		log.info("用户：{} 登录失败，异常：{}", username, exception.getLocalizedMessage());
		// 在请求线程上取好：异步线程里请求可能已结束。开关关时仍是 getRemoteAddr()（经网关部署时恒为网关容器地址），
		// 开时是 openresty 覆盖写的真实客户端（CARD-06）。
		String remoteAddr = ClientIpResolver.resolve(request, request::getRemoteAddr);
		TenantAwareAsync.runAsync(() -> {
			TenantContextHolder.setTenantId(CommonConstants.SYSTEM_TENANT_ID);
			SysLogLoginPayload sysLogLogin = new SysLogLoginPayload();
			sysLogLogin.setType(CommonConstants.LOG_TYPE_9);
			sysLogLogin.setGrantType(grantType);
			sysLogLogin.setCreateBy(username);
			sysLogLogin.setRemoteAddr(remoteAddr);
			sysLogLogin.setRequestUri(URLUtil.getPath(request.getRequestURI()));
			sysLogLogin.setUserAgent(request.getHeader("user-agent"));
//			sysLogLogin.setParams(HttpUtil.toParams(request.getParameterMap()));
			sysLogLogin.setException(exceptionOAuth2.getError().getErrorCode());
			String address = IpUtils.getAddresses(sysLogLogin.getRemoteAddr());
			sysLogLogin.setAddress(address);
			logLoginApi.saveLogLogin(sysLogLogin, SecurityConstants.FROM_IN);
		});
		// 写出错误信息
		sendErrorResponse(request, response, exception);
	}

	private void sendErrorResponse(HttpServletRequest request, HttpServletResponse response,
								   AuthenticationException exception) throws IOException {
		ServletServerHttpResponse httpResponse = new ServletServerHttpResponse(response);
		httpResponse.setStatusCode(HttpStatus.UNAUTHORIZED);
		String errorMessage;
		String errorCode = null;

		if (exception instanceof OAuth2AuthenticationException authorizationException) {
			errorCode = authorizationException.getError().getErrorCode();
			errorMessage = StrUtil.isBlank(authorizationException.getError().getDescription())
					? errorCode
					: authorizationException.getError().getDescription();
		}
		else {
			errorMessage = exception.getLocalizedMessage();
		}
		// 认不出的错误（数据库异常等被原样包成 OAuth2AuthenticationException）不把异常原文回给客户端：
		// 错误码只认 OAuth2 风格的短标识，其余记服务端日志、对外统一一句话
		if (errorCode == null || !OAUTH2_ERROR_CODE.matcher(errorCode).matches()) {
			log.error("登录失败（内部错误）: {}", errorMessage, exception);
			errorCode = null;
			errorMessage = "登录失败，请稍后重试";
		}

		// errCode 给前端分支用：credentials_expired = 首次登录 / 重置后须先改密（PUT /upms/user/password/expired）
		R<Object> body = R.failed(errorMessage);
		body.setErrCode(errorCode);
		errorConverter().write(body, MediaType.APPLICATION_JSON, httpResponse);
	}
}
