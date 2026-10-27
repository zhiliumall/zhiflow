/*
 * Copyright (C) 2024 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.config;

import com.alibaba.ttl.threadpool.TtlExecutors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 大屏聚合接口异步执行器配置。
 * <p>
 * 大屏 5 路统计 SQL 原先落 {@code ForkJoinPool.commonPool()}：commonPool 线程长期复用且不受 TTL 管理，
 * 租户上下文（{@code TenantContextHolder}，TransmittableThreadLocal）不会随任务还原，导致 search_path
 * 落到陈旧 / 他租户 schema，非确定性跨租户读。
 * <p>
 * 这里自建有界线程池并用 {@link TtlExecutors#getTtlExecutorService} 包装，对每个任务做 TTL snapshot-replay，
 * 还原提交时的租户上下文。低频大屏无需 mall-api 的虚拟线程 + 连接预算信号量整套。
 */
@Configuration
public class LargeScreenAsyncConfig {

    @Bean("largeScreenExecutor")
    public ExecutorService largeScreenExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                8, 8,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "largescreen-");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());   // 满了退回调用线程，不丢统计块
        return TtlExecutors.getTtlExecutorService(pool);      // 关键：TTL replay，每任务还原租户上下文
    }
}
