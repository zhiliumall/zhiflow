package com.zhiflow.upms.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * {@code LogApi#saveLog} 的请求投影 —— 取代把 {@code SysLog} 实体当请求体。
 *
 * <p>字段 = {@code zhiflow-common-log} 的 {@code SysLogUtils#getSysLog} 与
 * {@code SysLogAspect#around} 真正会 set 的那一组（2026-09-14 逐行核过），
 * 恰好是 {@code SysLog} <b>自己声明</b>的全部字段。
 *
 * <p>不带的是 {@code id}（服务端 {@code IdType.ASSIGN_ID} 自己生成）与 {@code TenantEntity} 的
 * {@code tenantId} / {@code delFlag} / {@code createTime} / {@code updateTime}
 * —— 后四个由 MyBatis-Plus 的 {@code FieldFill} 在服务端写入，调用方传什么都会被覆盖。
 * 特别是 {@code tenantId}：服务端 {@code SysLogController#save} 在租户上下文为空时
 * 显式回落到系统租户，那段规则必须留在服务端。
 */
@Data
public class SysLogPayload implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	/** 日志类型：'0' 正常 '9' 异常。 */
	private String type;

	private String title;

	/** 操作内容（人话描述）。 */
	private String content;

	private String createId;

	private String createBy;

	/** 来源 IP（网关 {@code X-Client-IP}，回落 {@code IpUtils}）。 */
	private String remoteAddr;

	private String userAgent;

	private String requestUri;

	/** HTTP 方法。 */
	private String method;

	/** 提交的数据（已按 {@code SysLogParamsCollector} 的规则采集/脱敏）。 */
	private String params;

	/** 执行耗时（毫秒）。 */
	private Long time;

	private String exception;

	private String serviceId;
}
