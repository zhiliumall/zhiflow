package com.zhiflow.common.lock.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redisson 配置属性
 *
 * @date 2025-12-22
 */
@Data
@ConfigurationProperties(prefix = "zhiflow.redisson")
public class RedissonProperties {

	/**
	 * 连接池大小
	 */
	private int connectionPoolSize = 64;

	/**
	 * 最小空闲连接数
	 */
	private int connectionMinimumIdleSize = 10;

	/**
	 * 看门狗超时时间（毫秒），默认 30 秒
	 */
	private long lockWatchdogTimeout = 30000;

}
