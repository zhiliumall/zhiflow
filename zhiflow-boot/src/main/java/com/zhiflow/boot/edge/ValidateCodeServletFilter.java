package com.zhiflow.boot.edge;

import cloud.tianai.captcha.cache.CacheStore;
import cn.hutool.core.util.StrUtil;
import tools.jackson.databind.ObjectMapper;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.exception.ValidateCodeException;
import com.zhiflow.common.core.util.R;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * 网关 {@code ValidateCodeGatewayFilter} 的 servlet 版：登录（{@code /oauth2/token}）
 * 前的验证码校验。**语义逐字照搬**，逐条对照见方法内注释与
 *
 * <h2>为什么少了它比报错更糟</h2>
 * <p>auth 侧不重复校验验证码 —— 网关（或本 filter）是唯一一道。少了它登录照常能通，
 * 只是防爆破静默消失。
 *
 * <h2>豁免逻辑（权限矩阵金丝雀依赖，逐字保留）</h2>
 * <ol>
 * <li>路径不含 {@code /oauth2/token}（大小写不敏感、子串匹配，与网关
 *     {@code StrUtil.containsAnyIgnoreCase} 一致）→ 放行；</li>
 * <li>Basic 认证头里的 clientId 在 {@code gateway.ignore-clients}（swagger / api）→ 放行
 *     —— C 端 {@code api} 客户端与权限矩阵金丝雀全靠这条免验证码；</li>
 * <li>{@code grant_type} 为 {@code refresh_token} → 放行。</li>
 * </ol>
 *
 * <h2>与网关版的三处实现差异（语义不变，见迁移文档「差异清单」）</h2>
 * <ul>
 * <li>参数来源：网关手工解析缓存的 body；servlet 容器对 form POST 原生解析
 *     （{@code getParameter} 合并了 query 与 body —— 与授权服务器
 *     {@code OAuth2EndpointUtils.getParameters} 看到的口径一致）；</li>
 * <li>不再 {@code redisTemplate.setKeySerializer(...)}：注入的主 redisTemplate 的
 *     key 序列化器本来就是 String（{@code RedisTemplateConfig}），而在单体里改共享 bean
 *     的序列化器是全进程副作用，网关能这么干只因它独占自己的进程；</li>
 * <li>Basic 头缺失/非法时不再抛异常打断（网关经 {@code WebUtils.getClientId} 抛
 *     {@code CheckedException}），而是按「非豁免客户端」继续走验证码校验 ——
 *     拒绝面只紧不松。</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class ValidateCodeServletFilter implements Filter {

	private final GatewayParityProperties configProperties;

	private final ObjectMapper objectMapper;

	private final RedisTemplate<String, Object> redisTemplate;

	private final CacheStore cacheStore;

	@Override
	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
			throws IOException, ServletException {
		if (!(servletRequest instanceof HttpServletRequest request)
				|| !(servletResponse instanceof HttpServletResponse response)) {
			chain.doFilter(servletRequest, servletResponse);
			return;
		}

		// 不是登录请求，直接向下执行
		if (!StrUtil.containsAnyIgnoreCase(request.getRequestURI(), SecurityConstants.OAUTH_TOKEN_URL)) {
			chain.doFilter(servletRequest, servletResponse);
			return;
		}

		// 终端设置不校验，直接向下执行（gateway.ignore-clients，与网关逐字一致）
		boolean isIgnoreClient = configProperties.getIgnoreClients() != null
				&& configProperties.getIgnoreClients().contains(clientIdOrNull(request));
		if (isIgnoreClient) {
			chain.doFilter(servletRequest, servletResponse);
			return;
		}

		String grantType = request.getParameter("grant_type");

		// 刷新token，直接向下执行
		if (StrUtil.equals(SecurityConstants.REFRESH_TOKEN, grantType)) {
			chain.doFilter(servletRequest, servletResponse);
			return;
		}

		try {
			checkCode(request);
		}
		catch (Exception e) {
			writeErrorResponse(response, e.getMessage());
			return;
		}

		chain.doFilter(servletRequest, servletResponse);
	}

	/**
	 * 从 Basic 认证头解出 clientId。语义对应 {@code WebUtils.getClientId}，
	 * 差异：解析失败返回 {@code null}（按非豁免客户端继续校验），不抛异常，见类注释。
	 */
	static String clientIdOrNull(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith("Basic ")) {
			return null;
		}
		try {
			String token = new String(Base64.getDecoder().decode(header.substring(6).getBytes(StandardCharsets.UTF_8)),
					StandardCharsets.UTF_8);
			int delim = token.indexOf(':');
			return delim < 0 ? null : token.substring(0, delim);
		}
		catch (IllegalArgumentException _) {
			return null;
		}
	}

	/** 写入错误响应：与网关一致的 428 + {@code R.failed(message)}。 */
	private void writeErrorResponse(HttpServletResponse response, String message) throws IOException {
		response.setStatus(428); // HttpStatus.PRECONDITION_REQUIRED，与网关逐字一致
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		try {
			response.getOutputStream().write(objectMapper.writeValueAsBytes(R.failed(message)));
		}
		catch (JacksonException e) {
			log.error("对象输出异常", e);
			response.getOutputStream().write("{\"code\":1,\"msg\":\"系统错误\"}".getBytes(StandardCharsets.UTF_8));
		}
	}

	/**
	 * 检查code —— 与网关 {@code ValidateCodeGatewayFilter#checkCode} 逐字同构，
	 * 仅把 {@code paramMap.get(...)} 换成 {@code request.getParameter(...)}。
	 */
	@SneakyThrows
	private void checkCode(HttpServletRequest request) {
		String code = request.getParameter("code");

		if (StrUtil.isBlank(code)) {
			throw new ValidateCodeException("验证码不能为空");
		}

		String randomStr = request.getParameter("randomStr");
		String grantType = request.getParameter("grant_type");
		if (StrUtil.equals(SecurityConstants.PASSWORD, grantType)) {// 密码方式登录改为滑块验证，不再验证短信验证码
			String key = "captcha:secondary:".concat(code);
			if (Objects.isNull(cacheStore.getAndRemoveCache(key))) {
				throw new ValidateCodeException("滑块验证码未通过");
			}
			redisTemplate.delete(key);
			return;
		}

		String key = CacheConstants.VER_CODE_DEFAULT + randomStr;

		if (!redisTemplate.hasKey(key)) {
			throw new ValidateCodeException("验证码不合法");
		}

		Object codeObj = redisTemplate.opsForValue().get(key);

		if (codeObj == null) {
			throw new ValidateCodeException("验证码不合法");
		}

		String saveCode = codeObj.toString();
		if (StrUtil.isBlank(saveCode)) {
			redisTemplate.delete(key);
			throw new ValidateCodeException("验证码不合法");
		}

		if (!StrUtil.equals(saveCode, code)) {
			throw new ValidateCodeException("验证码不合法");
		}

		redisTemplate.delete(key);
	}
}
