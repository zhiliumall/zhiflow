package com.zhiflow.common.search.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@ConfigurationProperties(prefix = "meilisearch")
@ConditionalOnProperty(name = "meilisearch.enabled", havingValue = "true")
@ComponentScan("com.zhiflow.common.search")
@Data
public class MeiliSearchConfig {
    private String hostUrl;

    private String searchKey;

    private String adminKey;

    private String aiKey;
    /**
     * 语义搜索权重 (混合搜索中语义向量的占比)
     * 取值范围: [0.0, 1.0]
     * 0.0 = 仅关键词搜索
     * 1.0 = 仅语义搜索
     * 默认: 0.3 (30% 语义 + 70% 关键词)
     */
    private Double semanticRatio = 0.3;

    /**
     * 搜索结果数量限制
     * 默认: 100
     */
    private Integer limit = 100;

    /**
     * Cache TTL for Meilisearch health checks (client.isHealthy -> GET /health).
     *
     * This prevents issuing a /health request before every operation.
     * Set to 0 to disable caching (legacy behavior).
     */
    private Long healthCheckTtlMs = 30_000L;

    /**
     * When the last health check failed, use a shorter TTL to re-check sooner.
     */
    private Long healthCheckFailureTtlMs = 2_000L;
}
