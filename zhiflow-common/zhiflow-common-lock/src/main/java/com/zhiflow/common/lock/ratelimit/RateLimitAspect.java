package com.zhiflow.common.lock.ratelimit;

import com.zhiflow.common.core.exception.TooManyRequestsException;
import com.zhiflow.common.core.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

/**
 * {@link RateLimit} 的实现：Redisson {@code RRateLimiter}（OVERALL，多实例共享），key =
 * {@code ratelimit:{规则名}:{维度}:{值}}，空闲两个窗口后自动过期。
 */
@Slf4j
@Aspect
public class RateLimitAspect {

	static final String KEY_PREFIX = "ratelimit:";

	private final RedissonClient redissonClient;

	private final RateLimitProperties properties;

	private final ObjectProvider<RateLimitPrincipalResolver> principalResolver;

	public RateLimitAspect(RedissonClient redissonClient, RateLimitProperties properties,
			ObjectProvider<RateLimitPrincipalResolver> principalResolver) {
		this.redissonClient = redissonClient;
		this.properties = properties;
		this.principalResolver = principalResolver;
	}

	@Around("@annotation(rateLimit)")
	public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
		if (properties.isEnabled() && !tryAcquire(rateLimit)) {
			throw new TooManyRequestsException(rateLimit.message());
		}
		return joinPoint.proceed();
	}

	boolean tryAcquire(RateLimit rateLimit) {
		long permits = rateLimit.permits();
		long period = rateLimit.periodSeconds();
		RateLimitProperties.Rule rule = properties.getRules().get(rateLimit.name());
		if (rule != null) {
			permits = rule.getPermits() != null ? rule.getPermits() : permits;
			period = rule.getPeriodSeconds() != null ? rule.getPeriodSeconds() : period;
		}
		String key = KEY_PREFIX + rateLimit.name() + ":" + dimensionValue(rateLimit.by());
		try {
			RRateLimiter limiter = redissonClient.getRateLimiter(key);
			// 已存在时不覆盖（配置改了阈值，等旧桶空闲过期后生效）
			limiter.trySetRate(RateType.OVERALL, permits, Duration.ofSeconds(period), Duration.ofSeconds(period * 2));
			return limiter.tryAcquire();
		}
		catch (RuntimeException e) {
			log.warn("限流检查失败，按放行处理 key={}：{}", key, e.toString());
			return true;
		}
	}

	String dimensionValue(RateLimit.Dimension dimension) {
		return switch (dimension) {
			case GLOBAL -> "global";
			case USER -> {
				RateLimitPrincipalResolver resolver = principalResolver.getIfAvailable();
				String principal = resolver == null ? null : resolver.currentPrincipal();
				yield principal != null && !principal.isBlank() ? "user:" + principal : "ip:" + clientIp();
			}
			case IP -> "ip:" + clientIp();
		};
	}

	private static String clientIp() {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		if (!(attributes instanceof ServletRequestAttributes servlet)) {
			return "unknown";
		}
		HttpServletRequest request = servlet.getRequest();
		String ip = ClientIpResolver.trusted(request);
		return ip == null || ip.isBlank() ? "unknown" : ip;
	}
}
