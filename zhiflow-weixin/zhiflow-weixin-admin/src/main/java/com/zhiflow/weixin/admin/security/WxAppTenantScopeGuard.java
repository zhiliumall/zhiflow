package com.zhiflow.weixin.admin.security;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.common.security.util.SecurityUtils;
import com.zhiflow.weixin.admin.mapper.WxAppMapper;
import com.zhiflow.weixin.common.entity.WxApp;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashSet;
import java.util.Set;

/**
 * 后台按 appId 取微信服务前，核对该 appId 属于当前租户（越权普查 H6）。
 *
 * <p><b>为什么挂在「取服务」处而不是改 {@link WxAppMapper#findByAppId}</b>：
 * {@code findByAppId} 故意不带租户条件，消息接收（{@code /portal}）、开放平台回调
 * （{@code /open/notify}）、授权跳转（{@code /open/auth}）、gRPC / 内部调用都靠它按 appId
 * 跨租户找到公众号。改它会把这些合法路径一起打断，所以只在<b>后台登录用户发起的请求</b>上加一道归属校验：
 * {@code WxMaConfiguration.getMaService}、{@code WxMpConfiguration.getMpService}、
 * 开放平台 {@code getWx*ServiceByAppid} 在返回服务前都先调 {@link #check(String)}。
 *
 * <p>只在下面全部成立时才校验，其余一律放行（与改动前一致）：
 * <ol>
 *   <li>开关 {@value #ENABLED} 明确为 true（缺失、写错都按关）；</li>
 *   <li>当前线程上有 HTTP 请求（gRPC、MQ、定时任务、消息路由线程没有）；</li>
 *   <li>请求头 {@code from} 不是内部调用标记（{@code @Inside} 的服务间调用、进程内
 *       {@code InternalCallContext} 本地调用都带它，网关会删掉外部请求的这个头）；</li>
 *   <li>有后台登录用户（匿名的消息接收 / 回调 / 授权跳转没有）；</li>
 *   <li>登录用户不是平台超管（{@code type = USER_TYPE_S}，与 {@code SysTenantController#update}
 *       的判定相同；超管本来就要能跨租户操作）。</li>
 * </ol>
 *
 * <p>校验本身：按 appId 主键查 {@code wx_app}，并显式要求 {@code tenant_id} 等于当前租户。
 * 不经 {@code WxAppService}：它的 {@code getById} 与 {@code findByAppId} 共用一个按 appId 的缓存，
 * 会读到不带租户条件查出来的行。用 {@code selectById} 按主键查，本校验只管租户归属。
 * 当前租户取不到时拒绝。
 */
@Slf4j
@Component
public class WxAppTenantScopeGuard {

	/** 开关：默认关；只认明确的布尔 true。 */
	public static final String ENABLED = "zhiflow.security.weixin-appid-tenant-scope.enabled";

	/** 同一请求里已通过校验的 appId，避免一次请求里反复取服务时重复查库。 */
	static final String VERIFIED_ATTR = WxAppTenantScopeGuard.class.getName() + ".verified";

	private final WxAppMapper wxAppMapper;

	private final Environment environment;

	public WxAppTenantScopeGuard(WxAppMapper wxAppMapper, Environment environment) {
		this.wxAppMapper = wxAppMapper;
		this.environment = environment;
	}

	public boolean enabled() {
		try {
			return Boolean.TRUE.equals(environment.getProperty(ENABLED, Boolean.class, false));
		}
		catch (RuntimeException ex) {
			log.warn("{} 的值无法解析为布尔，按关闭处理: {}", ENABLED, ex.toString());
			return false;
		}
	}

	/**
	 * appId 不属于当前租户时抛 {@link AccessDeniedException}（全局异常处理映射为 403）。
	 */
	public void check(String appId) {
		if (!enabled()) {
			return;
		}
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
			return;
		}
		HttpServletRequest request = servletAttributes.getRequest();
		if (StrUtil.equals(SecurityConstants.FROM_IN, request.getHeader(SecurityConstants.FROM))) {
			return;
		}
		BaseUser user = SecurityUtils.getUser();
		if (user == null) {
			return;
		}
		if (StrUtil.equals(CommonConstants.USER_TYPE_S, user.getType())) {
			return;
		}
		Set<String> verified = verifiedSet(attributes);
		if (appId != null && verified.contains(appId)) {
			return;
		}
		String tenantId = TenantContextHolder.getTenantId();
		if (StrUtil.isBlank(tenantId) || StrUtil.isBlank(appId)) {
			log.warn("微信应用归属校验拒绝：租户或 appId 为空 userId={} tenantId={} appId={}", user.getId(), tenantId, appId);
			throw new AccessDeniedException("无权操作该微信应用");
		}
		WxApp wxApp = wxAppMapper.selectById(appId);
		if (wxApp == null || !StrUtil.equals(tenantId, wxApp.getTenantId())) {
			log.warn("微信应用归属校验拒绝：appId 不属于当前租户 userId={} tenantId={} appId={}", user.getId(), tenantId, appId);
			throw new AccessDeniedException("无权操作该微信应用");
		}
		verified.add(appId);
	}

	@SuppressWarnings("unchecked")
	private static Set<String> verifiedSet(RequestAttributes attributes) {
		Object existing = attributes.getAttribute(VERIFIED_ATTR, RequestAttributes.SCOPE_REQUEST);
		if (existing instanceof Set<?> set) {
			return (Set<String>) set;
		}
		Set<String> created = new HashSet<>();
		attributes.setAttribute(VERIFIED_ATTR, created, RequestAttributes.SCOPE_REQUEST);
		return created;
	}

}
