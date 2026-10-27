/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 文件存放目录配置
 *
 * @author
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "base.home-dir")
public class HomeDirConfigProperties {

	/**
	 *
	 */
	private String windows;

	private String linux;

	public String getHomeDir(){
		String os = System.getProperty("os.name");
		if(os.toLowerCase().startsWith("win")){
			return this.windows;
		}else{
			return this.linux;
		}
	}
}
