package com.zhiflow.upms.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * {@code LogLoginApi#saveLogLogin} 的请求投影 —— 取代把 {@code SysLogLogin} 实体当请求体。
 *
 * <p>两个调用方都在 {@code zhiflow-auth}：{@code BaseAuthenticationSuccessEventHandler}
 * 与 {@code BaseAuthenticationFailureEventHandler}（2026-09-14 逐行核过）。
 * 字段 = 二者 set 的并集，等于 {@code SysLogLogin} 自己声明的全部字段再加一个 {@code tenantId}。
 *
 * <p>{@code tenantId} 是个<b>例外</b>：虽然它来自 {@code TenantEntity}，但成功登录那条
 * 确实显式 {@code setTenantId(userInfo.getTenantId())} —— 登录发生在租户上下文建立<b>之前</b>，
 * 不显式带过去就会落到错的租户。所以这个字段必须在请求里。
 *
 * <p>{@code params} 在两个调用方里都是<b>注释掉</b>的（{@code // sysLogLogin.setParams(...)}），
 * 仍然保留字段：注释掉的那行还在源码里，随时可能被放开，而那时候契约里得有位置。
 */
@Data
public class SysLogLoginPayload implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	/** 日志类型：'0' 登录成功 '9' 登录失败。 */
	private String type;

	/** 登录类型（OAuth2 grant_type）。 */
	private String grantType;

	private String createId;

	private String createBy;

	private String remoteAddr;

	private String userAgent;

	private String requestUri;

	/** 现状：两个调用方都把这一行注释掉了，恒为 null。 */
	private String params;

	/** IP 归属地。 */
	private String address;

	/** 失败原因（OAuth2 errorCode）。成功那条为 null。 */
	private String exception;

	/**
	 * 所属租户。⚠️ 必须显式带：登录发生在租户上下文建立之前，
	 * 成功事件里是从 {@code userInfo.getTenantId()} 取的。
	 */
	private String tenantId;
}
