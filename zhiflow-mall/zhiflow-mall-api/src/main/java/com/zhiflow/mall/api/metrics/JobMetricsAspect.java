package com.zhiflow.mall.api.metrics;

import com.zhiflow.common.lock.aspect.DistributedLockAspect;
import com.zhiflow.common.lock.exception.DistributedLockException;
import com.zhiflow.mall.api.job.JobResult;
import com.zhiflow.mall.common.observability.JobFailedException;
import com.zhiflow.mall.common.observability.JobMetrics;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.beans.Introspector;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

/**
 * mall-api 定时任务（{@code com.zhiflow.mall.api.job} 包下的 {@code @Scheduled}）的运行指标：
 * {@code job_runs_total{job,outcome}}、{@code job_duration_seconds{job}}。job = 任务类的 bean 名（类名首字母小写），
 * 与 {@code job_item_failures_total{job}} 同一个标签值。
 *
 * <p>outcome：返回 {@link JobResult#success()} = false 记 failure；抛异常记 error；任务锁被占 / 方法体主动整轮跳过记 skipped。
 * 打点不改变任务行为：返回值与异常原样交回调度器。
 */
@Slf4j
@Aspect
@Component
@Order(DistributedLockAspect.LOCK_ASPECT_ORDER - 5)
public class JobMetricsAspect {

	static final String JOB_PACKAGE = "com.zhiflow.mall.api.job";

	private final ApplicationContext context;

	public JobMetricsAspect(ApplicationContext context) {
		this.context = context;
	}

	@Around("@annotation(org.springframework.scheduling.annotation.Scheduled) && within(com.zhiflow.mall.api.job..*)")
	public Object around(ProceedingJoinPoint pjp) throws Throwable {
		String job = jobName(((MethodSignature) pjp.getSignature()).getMethod().getDeclaringClass());
		long start = System.nanoTime();
		// 清掉本线程可能残留的「整轮跳过」标记（调度线程是复用的）
		JobMetrics.consumeSkipped();
		try {
			Object result = pjp.proceed();
			// 任务在方法体里主动整轮跳过（如业务开关暂停）→ skipped，不是 success
			JobMetrics.record(job, JobMetrics.consumeSkipped() ? JobMetrics.SKIPPED : outcomeOf(result),
					System.nanoTime() - start);
			return result;
		}
		catch (Throwable t) {
			JobMetrics.consumeSkipped();
			JobMetrics.record(job, outcomeOf(t), System.nanoTime() - start);
			throw t;
		}
	}

	static String outcomeOf(Object result) {
		if (result instanceof JobResult r && !r.success()) {
			return JobMetrics.FAILURE;
		}
		return JobMetrics.SUCCESS;
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

	static String jobName(Class<?> jobClass) {
		return Introspector.decapitalize(ClassUtils.getUserClass(jobClass).getSimpleName());
	}

	/** 启动后把全部任务的指标预注册为 0：「查不到」= 埋点坏了，而不是「还没跑过」。 */
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
			if (type == null || !ClassUtils.getUserClass(type).getName().startsWith(JOB_PACKAGE + ".")) {
				continue;
			}
			try {
				Class<?> user = ClassUtils.getUserClass(type);
				ReflectionUtils.doWithMethods(user, m -> jobs.add(jobName(user)),
						(Method m) -> AnnotatedElementUtils.hasAnnotation(m, Scheduled.class));
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
