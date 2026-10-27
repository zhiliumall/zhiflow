package com.zhiflow.mall.admin.observability.job;

import com.zhiflow.common.lock.aspect.DistributedLockAspect;
import com.zhiflow.common.lock.exception.DistributedLockException;
import com.zhiflow.mall.common.observability.JobFailedException;
import com.zhiflow.mall.common.observability.JobMetrics;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

/**
 * mall-admin {@code @Scheduled} 任务的运行指标：{@code job_runs_total{job,outcome}}、
 * {@code job_duration_seconds{job}}，job = {@code 类名#方法名}。
 *
 * <p>范围只到 {@code ..mall.admin.task..} 与 {@code ..mall.admin.observability..} 两个包：那里的定时方法全是 public、所在类都不是 final，
 * 被 CGLIB 代理是安全的（其中大部分本来就因 {@code @DistributedLock} / {@code @DS} 被代理过）。
 *
 * <p>顺序排在分布式锁切面<b>外面</b>（{@code LOCK_ASPECT_ORDER − 5}）：锁被占时锁切面抛 {@link DistributedLockException}，
 * 这里记成 {@code skipped}（上一轮还没跑完，不是失败）；但也不能越过 {@code ExposeInvocationInterceptor}（MIN_VALUE + 1），
 * 理由见 {@link DistributedLockAspect} 类注释。
 *
 * <p>outcome：success 正常返回 / failure 抛 {@link JobFailedException}（部分租户失败）/ error 其它异常 /
 * skipped 锁被占，或方法体调了 {@link JobMetrics#markSkipped()} 后正常返回（业务开关暂停了结算，整轮跳过）。
 * 打点永远不改变任务的行为：异常原样抛回给调度器。
 */
@Slf4j
@Aspect
@Component
@Order(DistributedLockAspect.LOCK_ASPECT_ORDER - 5)
public class ScheduledJobMetricsAspect {

	static final String SCOPE = "com.zhiflow.mall.admin.task.";

	static final String OBSERVABILITY_SCOPE = "com.zhiflow.mall.admin.observability.";

	private final ApplicationContext context;

	public ScheduledJobMetricsAspect(ApplicationContext context) {
		this.context = context;
	}

	@Around("@annotation(org.springframework.scheduling.annotation.Scheduled)"
			+ " && (within(com.zhiflow.mall.admin.task..*) || within(com.zhiflow.mall.admin.observability..*))")
	public Object around(ProceedingJoinPoint pjp) throws Throwable {
		String job = pjp.getSignature().getDeclaringType().getSimpleName() + "#" + pjp.getSignature().getName();
		long start = System.nanoTime();
		// 清掉本线程可能残留的「整轮跳过」标记（调度线程是复用的）
		JobMetrics.consumeSkipped();
		try {
			Object result = pjp.proceed();
			// 任务在方法体里主动整轮跳过（业务开关暂停结算）→ skipped，不是 success
			JobMetrics.record(job, JobMetrics.consumeSkipped() ? JobMetrics.SKIPPED : JobMetrics.SUCCESS,
					System.nanoTime() - start);
			return result;
		}
		catch (Throwable t) {
			JobMetrics.consumeSkipped();
			JobMetrics.record(job, outcomeOf(t), System.nanoTime() - start);
			throw t;
		}
	}

	static String outcomeOf(Throwable t) {
		if (t instanceof DistributedLockException) {
			return JobMetrics.SKIPPED;
		}
		if (t instanceof JobFailedException) {
			return JobMetrics.FAILURE;
		}
		return JobMetrics.ERROR;
	}

	/** 启动后把范围内全部定时任务的指标预注册为 0：「查不到」= 埋点坏了，而不是「还没跑过」。 */
	@EventListener(ApplicationReadyEvent.class)
	public void preRegister() {
		Set<String> jobs = new TreeSet<>();
		for (String name : context.getBeanDefinitionNames()) {
			Class<?> type;
			try {
				type = context.getType(name, false);
			}
			catch (Exception e) {
				continue;
			}
			if (type == null) {
				continue;
			}
			try {
				Class<?> user = ClassUtils.getUserClass(type);
				String cn = user.getName();
				if (!cn.startsWith(SCOPE) && !cn.startsWith(OBSERVABILITY_SCOPE)) {
					continue;
				}
				ReflectionUtils.doWithMethods(user, (Method m) -> jobs.add(user.getSimpleName() + "#" + m.getName()),
						m -> AnnotatedElementUtils.hasAnnotation(m, Scheduled.class));
			}
			catch (Throwable t) {
				// 预注册只影响「为 0 时是否可见」；ApplicationReadyEvent 监听器抛出去会让整个应用启动失败
				log.warn("[任务指标] 预注册跳过 bean {}：{}", name, t.toString());
			}
		}
		jobs.forEach(JobMetrics::preRegister);
		log.info("[任务指标] 预注册 {} 个定时任务", jobs.size());
	}
}
