package com.zhiflow.common.log;

import com.alibaba.ttl.TtlRunnable;
import com.zhiflow.api.upms.LogApi;
import com.zhiflow.common.log.aspect.SysLogAspect;
import com.zhiflow.common.log.config.BaseLogProperties;
import com.zhiflow.common.log.error.ErrorLogRecorder;
import com.zhiflow.common.log.event.SysLogListener;
import com.zhiflow.common.log.fallback.AuditFallbackWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author 辰
 * @date 2023年10月27日11:28:14
 * <p>
 * 日志自动配置
 */
@EnableAsync
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BaseLogProperties.class)
@ConditionalOnWebApplication
public class LogAutoConfiguration {

	@Bean
	public AuditFallbackWriter auditFallbackWriter(BaseLogProperties logProperties) {
		return new AuditFallbackWriter(logProperties);
	}

	@Bean
	public SysLogListener sysLogListener(BaseLogProperties logProperties, LogApi logHttpClient,
			AuditFallbackWriter fallbackWriter) {
		return new SysLogListener(logHttpClient, logProperties, fallbackWriter);
	}

	/**
	 * @SysLog 审计日志专用有界 executor
	 *
	 * 原 saveSysLog 走默认 @Async（applicationTaskExecutor：8 线程 + 无界队列），upms 慢/挂时
	 * 8 线程全挂 120s + 队列无界堆积 → 内存单调增长、日志严重滞后。改为专用有界池：
	 * core2/max4/队列512。拒绝策略保持「弃最旧」，绝不用 CallerRunsPolicy
	 * （那会把 120s 阻塞回灌到发起 publishEvent 的用户请求线程，比现状更糟）。
	 *
	 * S2 改动：给拒绝策略包一层可观测。原先裸用 DiscardOldestPolicy，丢弃时连一行日志都没有
	 * —— 审计静默缺失完全不可发现，而 C 端全量补齐 @SysLog 后 upms 稍慢就会触发。
	 */
	@Bean("sysLogExecutor")
	public ThreadPoolTaskExecutor sysLogExecutor(AuditFallbackWriter fallbackWriter) {
		return newSysLogExecutor(fallbackWriter);
	}

	/**
	 * 2026-09-25 补：{@code TtlRunnable} 任务装饰器 —— 提交时抓取租户等上下文，执行时回放。
	 *
	 * <p>原先池线程是「复用」的，而 {@code TransmittableThreadLocal} 只在<b>建线程那一刻</b>按
	 * InheritableThreadLocal 语义拷贝一次：syslog- 的 2~4 个线程由最早几个请求线程建出来，
	 * 此后<b>一直带着那几个请求的租户</b>。审计日志 / 错误日志经 {@code TenantRequestInterceptor}
	 * 把这个租户写进上报 upms 的请求头，upms 按请求头落库 ⇒ 跨租户记错归属。
	 * {@code SysLogListener} 里再起的虚拟线程继承的也是这个旧值。mall-api / mall-admin / im 的业务池
	 * 早已用 TtlExecutors 包装，只有这个池漏了（技术栈清理第二波核查手写执行器时发现）。
	 */
	static ThreadPoolTaskExecutor newSysLogExecutor(AuditFallbackWriter fallbackWriter) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(512);
		executor.setThreadNamePrefix("syslog-");
		executor.setRejectedExecutionHandler(new ObservableDiscardOldestPolicy(fallbackWriter));
		executor.setTaskDecorator(runnable -> TtlRunnable.get(runnable, false, true));
		executor.initialize();
		return executor;
	}

	@Bean
	public SysLogAspect sysLogAspect(ApplicationEventPublisher publisher, BaseLogProperties logProperties) {
		return new SysLogAspect(publisher, logProperties);
	}

	/** 错误日志记录器，供业务 catch 块显式调用以补住「吞异常」的路径（只写本地日志）。 */
	@Bean
	public ErrorLogRecorder errorLogRecorder(BaseLogProperties logProperties) {
		return new ErrorLogRecorder(logProperties);
	}

	/**
	 * 「弃最旧」+ 可观测。语义与 {@link ThreadPoolExecutor.DiscardOldestPolicy} 完全一致，
	 * 只是丢弃前记一笔，把审计缺失从静默黑洞变成可告警事件。
	 */
	private record ObservableDiscardOldestPolicy(AuditFallbackWriter fallbackWriter)
			implements RejectedExecutionHandler {

		private static final ThreadPoolExecutor.DiscardOldestPolicy DELEGATE =
				new ThreadPoolExecutor.DiscardOldestPolicy();

		@Override
		public void rejectedExecution(Runnable runnable, ThreadPoolExecutor executor) {
			fallbackWriter.writeQueueOverflow();
			DELEGATE.rejectedExecution(runnable, executor);
		}
	}
}
