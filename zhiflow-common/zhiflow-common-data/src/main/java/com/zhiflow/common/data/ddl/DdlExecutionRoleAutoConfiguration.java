/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.data.ddl;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

/**
 * 装配 {@link DdlExecutionRole}（CARD-01）。物理连接的连接信息取自唯一的数据源配置
 * （{@code spring.datasource.url / username / password}，单数据源）——与连接池同一份 URL / 账号 / 口令，
 * 连接池换成什么实现都不受影响。只在真的要开物理连接时才去取（开关打开时的 DDL，以及不论开关的 Flyway 迁移——
 * 见 {@link DdlExecutionRole#migrationDataSource()}），取不到直接报错说明原因。
 */
@AutoConfiguration
public class DdlExecutionRoleAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public DdlExecutionRole ddlExecutionRole(Environment environment, ObjectProvider<DataSource> dataSource) {
		return new DdlExecutionRole(environment, () -> resolveSpec(environment), dataSource::getIfAvailable);
	}

	static DdlExecutionRole.ConnectionSpec resolveSpec(Environment environment) {
		String url = environment.getProperty("spring.datasource.url");
		if (url == null || url.isBlank()) {
			throw new IllegalStateException("没有 spring.datasource.url，开不了 DDL / 迁移用的物理连接");
		}
		return new DdlExecutionRole.ConnectionSpec(url, environment.getProperty("spring.datasource.username"),
				environment.getProperty("spring.datasource.password"));
	}
}
