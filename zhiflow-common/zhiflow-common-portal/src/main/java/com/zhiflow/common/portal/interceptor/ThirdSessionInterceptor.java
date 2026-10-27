/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.portal.interceptor;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.common.portal.constant.PortalConstants;
import com.zhiflow.common.portal.constant.PortalReturnCode;
import com.zhiflow.common.portal.entity.ThirdSession;
import com.zhiflow.common.portal.util.ApiUtil;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import com.zhiflow.common.security.annotation.Inside;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;

/**
 * ThirdSession拦截器，校验每个请求的ThirdSession
 */
@Slf4j
@Component
@AllArgsConstructor
public class ThirdSessionInterceptor implements AsyncHandlerInterceptor {

	private final RedisTemplate<String, Object> redisTemplate;

	/** 会话绝对过期上限：30 天。滑动续期(GETEX 120h)之上叠加此硬上限，消除活跃会话永不过期 */
	private static final long SESSION_ABSOLUTE_MAX_MILLIS = 30L * 24 * 60 * 60 * 1000;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		HandlerMethod method;
		if(handler instanceof HandlerMethod handlerMethod) {
			method = handlerMethod;
		}else{
			return true;
		}
		//如果是服务间调用，直接放行。
		//⚠️ 必须判 value：@Inside(value=false) 是「对外开放」标记（pagedevise/aggregate 在用），
		//旧写法 inside != null 一律跳过 → 这些端点不解析也不 clear ThirdSessionHolder，
		//Tomcat 线程复用时匿名请求继承上一请求残留登录态 → 人群 scope 误判为专属
		//（2026-07-21 生产实测：装修取页矩阵对匿名间歇性返回专属版，重启后短暂正常随污染复发）。
		Inside inside =  method.getMethodAnnotation(Inside.class);
		if(inside != null && inside.value()){
			return Boolean.TRUE;
		}
		//判断访问的control是否添加ApiLogin注解
		ApiLogin apiLogin = resolveApiLogin(method);
		//获取header中的客户端类型
		String clientTypeHeader = ApiUtil.getClientType(request);
		String appIdHeader = ApiUtil.getAppId(request);
		//普通h5端、没有appId的微信H5、APP，只对@ApiLogin注解的接口进行Session校验
		if(PortalConstants.CLIENT_TYPE_H5.equals(clientTypeHeader) || PortalConstants.CLIENT_TYPE_H5_PC.equals(clientTypeHeader) ||
				(PortalConstants.CLIENT_TYPE_H5_WX.equals(clientTypeHeader) && StrUtil.isBlank(appIdHeader)) ||
				PortalConstants.CLIENT_TYPE_APP.equals(clientTypeHeader) ){
			if(apiLogin != null && apiLogin.mustLogin()){//此接口必须登录才能访问
				return this.judeSession(request, response, apiLogin);
			}else{
				TenantContextHolder.setTenantId(CommonConstants.DEFAULT_TENANT_ID);//租户固定为默认租户
				this.attachOptionalSession(request);
				return Boolean.TRUE;
			}
		}else{
			//小程序端或带有appId的微信H5，根据@ApiLogin注解判断是否需要session
			if(apiLogin != null && apiLogin.mustLogin()) {
				// 此接口必须登录才能访问
				return this.judeSession(request, response, apiLogin);
			} else {
				// 此接口不需要登录：租户固定为默认租户
				TenantContextHolder.setTenantId(CommonConstants.DEFAULT_TENANT_ID);

				// 尝试获取可选的session（如果用户已登录）
				this.attachOptionalSession(request);
				return Boolean.TRUE;
			}
		}
	}

	/**
	 * 可选登录接口：带了 third-session 就尝试挂上登录态，<b>挂之前按必须登录分支（{@link #judeSession}）的同一条规则
	 * 判断这份会话是否属于本次请求</b>。
	 *
	 * <p>此前这里只要 Redis 里取得到就直接挂：租户取的是 {@code tenant-id} 头、登录态却是另一个租户的。
	 * 于是 A 租户的用户带 {@code tenant-id: B} 访问任意可选登录接口，会以 A 的 userId 在 B 的 schema 里读写
	 * （如商品详情写足迹、砍价详情按 userId 取砍价单）。必须登录分支同样的请求会被 60001 拒掉。
	 *
	 * <p>不属于本次请求时<b>当作没带会话</b>（按匿名处理），而不是像必须登录分支那样回 60001：
	 * 可选登录接口本来就对匿名开放，拒掉只会让这个人连匿名能看的页面也看不了，安全上没有多得到什么。
	 * 属于本次请求时租户以会话为准（与 {@link #judeSession} 一致；头非空时两者本就相等，
	 * 实际只影响小程序分支「头为空兜 1」的情形）。没带会话 / 会话过期的行为逐字不变。
	 */
	private void attachOptionalSession(HttpServletRequest request) {
		String thirdSessionHeader = request.getHeader(PortalConstants.HEADER_THIRDSESSION);
		if(StrUtil.isBlank(thirdSessionHeader)){
			ThirdSessionHolder.clear();
			return;
		}
		//获取缓存中的ThirdSession
		String key = this.getSessionRedisKey(thirdSessionHeader);
		ThirdSession thirdSession = this.loadSessionWithAbsoluteCap(key, request);
		if(thirdSession == null) {//session过期
			ThirdSessionHolder.clear();
			return;
		}
		if(!sessionBelongsToRequest(request, thirdSession)){
			//不记会话串与 userId，只记两边的租户 / appId 与路径，够定位来源
			log.warn("可选登录接口会话与请求不匹配，按匿名处理 uri={} sessionTenant={} appIdHeader={} sessionAppId={}",
					request.getRequestURI(), thirdSession.getTenantId(), ApiUtil.getAppId(request), thirdSession.getAppId());
			ThirdSessionHolder.clear();
			return;
		}
		if(StrUtil.isNotBlank(thirdSession.getTenantId())){
			TenantContextHolder.setTenantId(thirdSession.getTenantId());//以会话租户为准
		}
		ThirdSessionHolder.setThirdSession(thirdSession);
	}

	/**
	 * 会话是否属于本次请求：会话必须属于默认租户（{@code tenant-id} 头不再参与）；
	 * {@code app-id} 头与会话 appId 都非空时必须相等。必须登录与可选登录两个分支共用这一条判据。
	 */
	private static boolean sessionBelongsToRequest(HttpServletRequest request, ThirdSession thirdSession) {
		if(!CommonConstants.DEFAULT_TENANT_ID.equals(thirdSession.getTenantId())){
			return false;
		}
		String appIdHeader = ApiUtil.getAppId(request);
		return !(StrUtil.isNotBlank(appIdHeader) && StrUtil.isNotBlank(thirdSession.getAppId())
				&& !thirdSession.getAppId().equals(appIdHeader));
	}

	/**
	 * 请求结束清掉登录态。
	 *
	 * <p>原先只在「下一个请求的 preHandle」里清，两处漏洞：{@code @Inside(true)} 内部接口直接放行、
	 * 根本走不到清理；请求处理完到下一个请求进来之间，登录态一直挂在 Tomcat 平台线程上
	 * （7/21「匿名请求继承上一请求残留登录态」同源）。租户由 {@code TenantContextHolderFilter} 在 finally 里清。
	 */
	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
			Exception ex) {
		ThirdSessionHolder.clear();
	}

	/**
	 * 异步请求：原线程在这里就交还给容器了，同样不能带着登录态走。异步派发回来时 preHandle 会重新解析。
	 */
	@Override
	public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
			Object handler) {
		ThirdSessionHolder.clear();
	}

	/**
	 * 解析生效的 {@link ApiLogin}：<b>方法级优先、类级回退</b>。
	 *
	 * <p>原先只读 {@code method.getMethodAnnotation(ApiLogin.class)}，于是<b>类级 {@code @ApiLogin}
	 * 在运行期一行都不生效</b>——写了类级注解的 controller 实际是裸奔，而且本地怎么测都测不出来
	 * （注解写着 mustLogin=true，行为却是放行）。更糟的是架构门禁 R1b 的判据里认类级注解
	 * （{@code method.getOwner().isAnnotatedWith(ANN_API_LOGIN)}），两边一叠加就是
	 * 「门禁绿、运行期无鉴权」。
	 *
	 * <p><b>净效果为零，逐个核对过</b>（2026-08-17 全仓扫描，48 处类级注解）：
	 * <ul>
	 *   <li>47 处是 {@code @ApiLogin(mustLogin = false)}。回退拿到它之后
	 *       {@code apiLogin != null && apiLogin.mustLogin()} 仍为 false，走的还是原来那条
	 *       「取 tenantId、可选解析 session」的放行分支，行为逐字不变。</li>
	 *   <li>唯一 {@code mustLogin = true} 的是 {@code RealNameInfoController}，它的 8 个方法
	 *       <b>每一个都补了方法级 {@code @ApiLogin}</b>，方法级优先，行为同样不变。</li>
	 * </ul>
	 * 也就是说这次改的是「下一个人只写类级注解时会不会裸奔」，而不是任何现有端点的行为。
	 * 新增第三种情形（类级 true + 某方法无方法级注解）时，该方法会从放行变为必须登录——
	 * 这正是注解字面意思，但它是行为变更，须在引入时单独核对。
	 */
	private static ApiLogin resolveApiLogin(HandlerMethod method) {
		ApiLogin apiLogin = method.getMethodAnnotation(ApiLogin.class);
		if(apiLogin != null){
			return apiLogin;
		}
		return method.getBeanType().getAnnotation(ApiLogin.class);
	}

	/**
	 * 校验session
	 * @param request
	 * @param response
	 * @return
	 * @throws IOException
	 */
	private boolean judeSession(HttpServletRequest request, HttpServletResponse response, ApiLogin apiLogin) throws IOException {
		//获取header中的ThirdSession
		String thirdSessionHeader = request.getHeader(PortalConstants.HEADER_THIRDSESSION);
		if(StrUtil.isNotBlank(thirdSessionHeader)){
			//获取缓存中的ThirdSession
			String key = this.getSessionRedisKey(thirdSessionHeader);
			//GETEX：取值+续期单次往返(与上面两分支一致)。注:续期从"校验通过后"提前到"取值即续",
			//校验失败也会续一次 TTL——session 本身仍合法只是请求头不匹配,无安全影响,换取每请求省一次 Redis 往返
			ThirdSession thirdSession = this.loadSessionWithAbsoluteCap(key, request);
			if(thirdSession == null) {//session过期(含绝对过期)
				R r = R.failed(PortalReturnCode.ERR_60001.getCode(), PortalReturnCode.ERR_60001.getMsg());
				this.writerPrint(response, r);
				return Boolean.FALSE;
			}else {
				//判断session是否属于当前tenantId、appId
				if(!sessionBelongsToRequest(request, thirdSession)){
					R r = R.failed(PortalReturnCode.ERR_60001.getCode(), PortalReturnCode.ERR_60001.getMsg());
					this.writerPrint(response, r);
					return Boolean.FALSE;
				}
				TenantContextHolder.setTenantId(thirdSession.getTenantId());//设置租户ID
				ThirdSessionHolder.setThirdSession(thirdSession);//设置thirdSession

				if(apiLogin != null && apiLogin.mustLogin()){
					//此接口必须登录商城才能访问
					return this.judeSessionUserMall(response, thirdSession);
				}else{
					return Boolean.TRUE;
				}
			}
		}else{
			R r = R.failed(PortalReturnCode.ERR_60002.getCode(), PortalReturnCode.ERR_60002.getMsg());
			this.writerPrint(response, r);
			return Boolean.FALSE;
		}
	}

	/**
	 * 校验session是否商城登录
	 * @param thirdSession
	 * @return
	 * @throws IOException
	 */
	private boolean judeSessionUserMall(HttpServletResponse response, ThirdSession thirdSession) throws IOException {
		String userId = thirdSession.getUserId();
		if(StrUtil.isBlank(userId)){
			R r = R.failed(PortalReturnCode.ERR_60003.getCode(), PortalReturnCode.ERR_60003.getMsg());
			this.writerPrint(response, r);
			return Boolean.FALSE;
		}
		return Boolean.TRUE;
	}

	/**
	 * 读取 session 并施加 30 天绝对过期上限。
	 * - GETEX 保持原有 120h 滑动续期；
	 * - 无 issuedAt 的历史/遗漏路径 session 以首次访问时间惰性补写签发时间(自此刻起计 30 天)，避免永续；
	 * - 已超 30 天绝对上限的直接删除并视为过期。
	 * @return 有效 session；null 表示不存在或已绝对过期
	 */
	private ThirdSession loadSessionWithAbsoluteCap(String key, HttpServletRequest request) {
		Object obj = redisTemplate.opsForValue().getAndExpire(key, CacheConstants.TIME_OUT_SESSION, TimeUnit.HOURS);
		if(obj == null) {
			return null;
		}
		ThirdSession session = JSONUtil.toBean(String.valueOf(obj), ThirdSession.class);
		long now = System.currentTimeMillis();
		Long issuedAt = session.getIssuedAt();
		if(issuedAt == null) {
			//历史/遗漏路径 session：以首次访问时间为签发时间惰性补写，30 天上限从此刻起算
			session.setIssuedAt(now);
			if(StrUtil.isBlank(session.getDevice())) {
				String ua = request.getHeader("User-Agent");
				session.setDevice(ua == null ? null : StrUtil.sub(ua, 0, 128));
			}
			redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(session), CacheConstants.TIME_OUT_SESSION, TimeUnit.HOURS);
			return session;
		}
		if(now - issuedAt > SESSION_ABSOLUTE_MAX_MILLIS) {
			//超绝对上限：删除会话，强制重新认证
			redisTemplate.delete(key);
			return null;
		}
		return session;
	}

	private void writerPrint(HttpServletResponse response, R r) throws IOException {
		//返回超时错误码，触发小程序重新登录
		response.setCharacterEncoding(CommonConstants.UTF8);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		PrintWriter writer = response.getWriter();
		writer.print(JSONUtil.parseObj(r));
		if(writer != null){
			writer.close();
		}
	}

	public static String getSessionRedisKey(String thirdSessionHeader){
		return StrUtil.format("{}{}::{}", CacheConstants.PUBLIC_CACHE, CacheConstants.THIRD_SESSION_BEGIN, thirdSessionHeader);
	}
}
