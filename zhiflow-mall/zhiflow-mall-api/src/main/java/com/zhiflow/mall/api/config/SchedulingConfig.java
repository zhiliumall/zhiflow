package com.zhiflow.mall.api.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * mall-api 的调度开关：订单自动取消 / 自动收货、支付单复查、库存预占兜底四个 {@code @Scheduled} 任务靠它触发。
 *
 * <p>{@code mall.dev.scheduled-tasks-enabled=false} 关掉整个调度（本机开发连共享库、装配冒烟测试时用），默认开。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mall.dev.scheduled-tasks-enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {
}
