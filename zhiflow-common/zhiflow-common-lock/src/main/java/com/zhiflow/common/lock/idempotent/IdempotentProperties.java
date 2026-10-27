package com.zhiflow.common.lock.idempotent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 幂等配置。
 */
@Data
@ConfigurationProperties(prefix = "idempotent")
public class IdempotentProperties {

	/**
	 * 总开关。关掉后 {@code @Idempotent} 完全不生效（代码留存），是本任务的一键回滚方式。
	 */
	private boolean enabled = true;

	/**
	 * 缺少 {@code Idempotency-Key} 头时是否拒绝请求。
	 *
	 * <p><b>默认 false（放行）。</b>前端 axios 拦截器尚未上线，此刻线上所有请求都不带这个头，
	 * 默认 true 会让五个接口一上线就全挂。前端就绪后再切 true，或用
	 * {@code @Idempotent(required = YES)} 逐端点灰度。
	 */
	private boolean requireKey = false;

	/** 幂等窗口秒数，默认 60。 */
	private int ttlSeconds = 60;

	/** Redis key 前缀 */
	private String keyPrefix = "idem";
}
