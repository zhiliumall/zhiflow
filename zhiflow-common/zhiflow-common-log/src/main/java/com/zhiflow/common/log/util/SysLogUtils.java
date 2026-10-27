package com.zhiflow.common.log.util;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.HttpUtil;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.ClientInfoUtils;
import com.zhiflow.common.core.util.IpUtils;
import com.zhiflow.common.log.config.BaseLogProperties;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.upms.common.dto.SysLogPayload;
import io.micrometer.common.util.StringUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Objects;

/**
 * 系统日志工具类
 *
 * @author
 */
@UtilityClass
public class SysLogUtils {

	/**
	 * 采集审计日志，params 仅含 query 参数。
	 *
	 * <p>保留此重载供非切面调用方使用。切面路径请用
	 * {@link #getSysLog(ProceedingJoinPoint, BaseLogProperties)}——它能拿到
	 * {@code @PathVariable} 与 {@code @RequestBody}，那是删除类接口唯一有价值的入参来源。
	 */
	public SysLogPayload getSysLog() {
		return getSysLog(null, null);
	}

	/**
	 * @param point      切点；为 null 时退化为仅采集 query 参数
	 * @param properties 日志配置；为 null 时退化为仅采集 query 参数
	 */
	public SysLogPayload getSysLog(ProceedingJoinPoint point, BaseLogProperties properties) {
		return getSysLog(point, properties, true);
	}

	/** Allows a sensitive operation to preserve request metadata while omitting its body. */
	public SysLogPayload getSysLog(ProceedingJoinPoint point, BaseLogProperties properties, boolean captureBody) {
		HttpServletRequest request = ((ServletRequestAttributes) Objects
				.requireNonNull(RequestContextHolder.getRequestAttributes())).getRequest();
		SysLogPayload sysLog = new SysLogPayload();
		sysLog.setCreateBy(Objects.requireNonNull(getUsername()));
		if(StringUtils.isBlank(sysLog.getCreateBy())){
			sysLog.setCreateBy(request.getParameter("username"));
		}else {
			sysLog.setCreateId(Objects.requireNonNull(getUserId()));
		}
		sysLog.setRemoteAddr(resolveRemoteAddr(request));
		// gRPC 服务端经 InternalCallContext 造的内部请求没有 URI（2026-09-16 短信全挂的直接原因）
		String requestUri = request.getRequestURI();
		sysLog.setRequestUri(requestUri == null ? null : URLUtil.getPath(requestUri));
		sysLog.setMethod(request.getMethod());
		sysLog.setUserAgent(request.getHeader("user-agent"));
		sysLog.setParams(point != null && properties != null
				? SysLogParamsCollector.collect(point, request, properties, captureBody)
				: HttpUtil.toParams(request.getParameterMap()));
		sysLog.setServiceId(getClientId());
		sysLog.setType(CommonConstants.LOG_TYPE_0);
		return sysLog;
	}

	/**
	 * 审计日志的来源地址：取网关 {@code IpLocationGlobalFilter} 注入的 {@code X-Client-IP}。
	 *
	 * <p>不能用 {@code request.getRemoteAddr()}——微服务前面隔着 nginx + 网关，拿到的恒为
	 * 网关容器内网地址（经网关部署时一律是 {@code 172.18.0.x}），审计上等于没有来源。真实客户端 IP
	 * 此前只能去翻 nginx access.log 的 {@code X-Forwarded-For}，2026-07-30 追查装修被覆盖
	 * 时就是这么绕的。
	 *
	 * <p>取值逻辑在 {@link ClientInfoUtils} / {@link IpUtils}，此处只消费。开关
	 * {@code zhiflow.perf.client-ip.enabled} 关（现状）时是网关按「XFF 最左侧第一个有效地址」解析的值——
	 * 最左段可伪造；开时是 openresty 覆盖写的真实客户端（CARD-06，见 {@code ClientIpResolver}）。
	 * 无网关上下文（本地直连、内部调用）时回退 {@link IpUtils#getIpAddr}。
	 */
	private String resolveRemoteAddr(HttpServletRequest request) {
		String clientIp = ClientInfoUtils.getClientLocation(request).getIp();
		return StringUtils.isNotBlank(clientIp) ? clientIp : IpUtils.getIpAddr(request);
	}

	/**
	 * 获取客户端
	 *
	 * @return clientId
	 */
	private String getClientId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			return null;
		}

		Object principal = authentication.getPrincipal();
		if (principal instanceof OAuth2AuthenticatedPrincipal auth2Authentication) {
			return MapUtil.getStr(auth2Authentication.getAttributes(), SecurityConstants.CLIENT_ID);
		}
		return null;
	}

	/**
	 * 获取用户ID
	 *
	 * @return username
	 */
	private String getUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			return null;
		}
		if(authentication instanceof AnonymousAuthenticationToken){
			return "";
		}
		if(authentication instanceof OAuth2AccessTokenAuthenticationToken token){
			BaseUser baseUser = MapUtil.get(token.getAdditionalParameters(), "user_info", BaseUser.class);
			return baseUser==null?"":baseUser.getId();
		}
		return "";
	}

	/**
	 * 获取用户名称
	 *
	 * @return username
	 */
	private String getUsername() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			return "";
		}
		return authentication.getName();
	}

}
