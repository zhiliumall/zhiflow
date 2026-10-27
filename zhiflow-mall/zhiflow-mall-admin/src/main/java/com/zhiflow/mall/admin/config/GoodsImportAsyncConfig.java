package com.zhiflow.mall.admin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 商品批量导入的专用线程池。
 *
 * <p>单开一个池而不是蹭默认 {@code @Async} 执行器：导入一批 500 条要跑几十秒，
 * 挂在共享池上会把审核通知这类短任务一起堵住。
 * 池子刻意开得很小——导入是写库密集型，并发跑几十个批次只会把 DB 拖垮。
 */
@Configuration
public class GoodsImportAsyncConfig {

	public static final String EXECUTOR = "goodsImportExecutor";

	@Bean(EXECUTOR)
	public Executor goodsImportExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(50);
		executor.setKeepAliveSeconds(120);
		executor.setThreadNamePrefix("goods-import-");
		// 队列满时由提交线程自己跑：宁可让「开始导入」这个请求变慢，也不能静默丢任务
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(60);
		executor.initialize();
		return executor;
	}
}
