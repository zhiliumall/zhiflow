/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.data.shardingtable.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 分表配置
 *
 * @author
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "base.shardingtable")
public class ShardingTableProperties {

	/**
	 * 动态表集合
	 */
	private List<String> tables = new ArrayList<>();
}