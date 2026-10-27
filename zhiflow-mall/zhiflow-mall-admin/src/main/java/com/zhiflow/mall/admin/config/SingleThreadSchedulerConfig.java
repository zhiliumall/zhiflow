package com.zhiflow.mall.admin.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 把 {@code @Scheduled} 的调度器钉成「一条线程、所有定时任务排队串行」—— 也就是 Boot 现在默认建的那一个。
 *
 * <p>为什么要显式写出来：{@code spring.threads.virtual.enabled=true} 时 Boot 会改建
 * {@code SimpleAsyncTaskScheduler}，cron 与固定速率任务<b>每次触发都新开一条线程</b>（固定延迟除外）。
 * mall-admin 有 20 个 cron 任务，整点同时触发链动奖励发放、奖励发放、推广活动状态，每分钟有退款结果轮询、
 * 装修定时发布，只有 7 个类带锁 —— 现在它们在一条线程上排队、永不并发；开关一开就会同时跑，
 * 同一个任务上一次没跑完、下一次到点也会再开一条。
 *
 * <p>有了这个 Bean，Boot 的默认调度器按 {@code @ConditionalOnMissingBean(TaskScheduler)} 退让，开关不再改变定时任务语义。
 * 用 Boot 自己的 {@link ThreadPoolTaskSchedulerBuilder} 构建（它不随开关变化），{@code spring.task.scheduling.*} 照旧生效。
 * {@code @Async} 默认执行器的候选名单（goodsImportExecutor / sysLogExecutor / taskScheduler）不变。
 */
@Configuration(proxyBeanMethods = false)
public class SingleThreadSchedulerConfig {

	@Bean(name = "taskScheduler")
	public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
		return builder.build();
	}

}
