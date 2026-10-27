package com.zhiflow.common.swagger.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;


/**
 * SwaggerProperties
 *
 * @author
 */
@Data
@ConfigurationProperties(prefix = "springdoc.info")
public class SwaggerProperties {

	/**
	 * 标题
	 **/
	private String title = "默认标题";
	/**
	 * 描述
	 **/
	private String description = "默认描述";
	/**
	 * 版本
	 */
	private String version = "1.0.0";

}
