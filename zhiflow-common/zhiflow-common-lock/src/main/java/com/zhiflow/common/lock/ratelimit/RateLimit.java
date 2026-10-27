package com.zhiflow.common.lock.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流（取代 Sentinel，挂在登录、下单、支付三类接口上）。
 *
 * <p>同一个 {@link #name()} + 维度值在 {@link #periodSeconds()} 秒内最多放行 {@link #permits()} 次，超出抛
 * {@code TooManyRequestsException}（HTTP 429）。阈值可在配置里按规则名覆盖：
 * <pre>
 * zhiflow:
 *   rate-limit:
 *     rules:
 *       order-create: { permits: 20, period-seconds: 60 }
 * </pre>
 * 基于 Redisson {@code RRateLimiter}，多实例共享计数。Redis 不可用时放行（限流是保护，不是准入）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

	/** 规则名：配置覆盖与 Redis key 都用它，如 {@code login}、{@code order-create}、{@code pay}。 */
	String name();

	/** 按什么计数。 */
	Dimension by() default Dimension.IP;

	/** 窗口内放行次数（配置里同名规则优先）。 */
	long permits() default 10;

	/** 窗口长度，秒（配置里同名规则优先）。 */
	long periodSeconds() default 60;

	/** 被限流时回给前端的提示。 */
	String message() default "操作太频繁，请稍后再试";

	enum Dimension {
		/** 按客户端 IP（只认可信代理链路解析出的地址，见 {@code ClientIpResolver#trusted}）。 */
		IP,
		/** 按当前登录用户；取不到用户时退回按 IP。用户由 {@link RateLimitPrincipalResolver} 提供。 */
		USER,
		/** 全局一个桶。 */
		GLOBAL
	}
}
