package com.zhiflow.common.lock.config;

import com.zhiflow.common.lock.properties.RedissonProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * Redisson 自动配置
 *
 * @date 2025-12-22
 */
@Slf4j
@AutoConfiguration(after = DataRedisAutoConfiguration.class)
@RequiredArgsConstructor
@EnableConfigurationProperties({ RedissonProperties.class })
public class RedissonAutoConfiguration {

	private final DataRedisProperties redisProperties;

	private final RedissonProperties redissonProperties;

	@Bean
	@ConditionalOnMissingBean
	public RedissonClient redissonClient() {
		// 调试日志：打印 RedissonProperties 配置值
		log.info("RedissonProperties 配置值 - connectionPoolSize: {}, connectionMinimumIdleSize: {}, lockWatchdogTimeout: {}",
			redissonProperties.getConnectionPoolSize(),
			redissonProperties.getConnectionMinimumIdleSize(),
			redissonProperties.getLockWatchdogTimeout());

		Config config = new Config();

		// 根据 Redis 配置选择单机或集群模式
		if (redisProperties.getCluster() != null) {
			configureCluster(config);
		}
		else {
			configureSingle(config);
		}

		log.info("Redisson 客户端初始化成功，模式: {}", redisProperties.getCluster() != null ? "集群" : "单机");

		return Redisson.create(config);
	}

	/**
	 * 配置单机模式
	 */
	private void configureSingle(Config config) {
		String address = "valkey://%s:%d".formatted(redisProperties.getHost(), redisProperties.getPort());
		log.info("Redisson 单机模式: address： {}", address);
		
		// 计算超时时间，默认 10 秒
		long timeoutMs = redisProperties.getTimeout() != null ? redisProperties.getTimeout().toMillis() : 10000L;
		long connectTimeoutMs = timeoutMs; // 连接超时使用相同的值
		
		SingleServerConfig serverConfig = config.useSingleServer().setAddress(address)
			.setDatabase(redisProperties.getDatabase())
			.setConnectTimeout((int) connectTimeoutMs) // 连接超时
			.setTimeout((int) timeoutMs) // 命令执行超时
			.setConnectionPoolSize(redissonProperties.getConnectionPoolSize())
			.setConnectionMinimumIdleSize(redissonProperties.getConnectionMinimumIdleSize())
			.setRetryAttempts(3) // 重试次数
			.setRetryInterval(1500); // 重试间隔（毫秒）

		if (StringUtils.hasText(redisProperties.getPassword())) {
			serverConfig.setPassword(redisProperties.getPassword());
		}
		
		log.info("Redisson 单机模式配置完成 - 连接超时: {}ms, 命令超时: {}ms", connectTimeoutMs, timeoutMs);
	}

	/**
	 * 配置集群模式
	 */
	private void configureCluster(Config config) {
		ClusterServersConfig clusterConfig = config.useClusterServers();

		redisProperties.getCluster().getNodes().forEach(node -> {
			clusterConfig.addNodeAddress("valkey://" + node);
		});

		// 计算超时时间，默认 10 秒
		long timeoutMs = redisProperties.getTimeout() != null ? redisProperties.getTimeout().toMillis() : 10000L;
		long connectTimeoutMs = timeoutMs; // 连接超时使用相同的值

		clusterConfig.setConnectTimeout((int) connectTimeoutMs) // 连接超时
			.setTimeout((int) timeoutMs) // 命令执行超时
			.setMasterConnectionPoolSize(redissonProperties.getConnectionPoolSize())
			.setSlaveConnectionPoolSize(redissonProperties.getConnectionPoolSize())
			.setRetryAttempts(3) // 重试次数
			.setRetryInterval(1500); // 重试间隔（毫秒）

		if (StringUtils.hasText(redisProperties.getPassword())) {
			clusterConfig.setPassword(redisProperties.getPassword());
		}
		
		log.info("Redisson 集群模式配置完成 - 连接超时: {}ms, 命令超时: {}ms", connectTimeoutMs, timeoutMs);
	}

}
