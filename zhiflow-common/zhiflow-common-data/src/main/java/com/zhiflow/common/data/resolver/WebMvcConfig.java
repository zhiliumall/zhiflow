package com.zhiflow.common.data.resolver;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Web MVC 全局配置
 *
 * @author
 * @date
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
	/**
	 * 增加请求参数解析器，对请求中的参数注入SQL
	 * @param argumentResolvers
	 */
	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
		argumentResolvers.add(new SqlFilterArgumentResolver());
	}

	/**
	 * 添加类型转换器和格式化器
	 * 用于处理GET请求中的日期时间参数转换
	 */
	@Override
	public void addFormatters(FormatterRegistry registry) {
		// LocalDateTime 转换器 - 支持多种格式
		registry.addConverter(new Converter<String, LocalDateTime>() {
			@Override
			public LocalDateTime convert(String source) {
				if (source == null || source.trim().isEmpty()) {
					return null;
				}
				source = source.trim();

				// 支持格式: yyyy-MM-dd HH:mm:ss
				if (source.matches("^\\d{4}-\\d{1,2}-\\d{1,2} {1}\\d{1,2}:\\d{1,2}:\\d{1,2}$")) {
					return LocalDateTime.parse(source, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
				}
				// 支持格式: yyyy-MM-dd'T'HH:mm:ss (ISO格式)
				else if (source.contains("T")) {
					return LocalDateTime.parse(source, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
				}
				// 支持格式: yyyy-MM-dd (自动补充时间为00:00:00)
				else if (source.matches("^\\d{4}-\\d{1,2}-\\d{1,2}$")) {
					return LocalDate.parse(source, DateTimeFormatter.ofPattern("yyyy-MM-dd")).atStartOfDay();
				}
				// 其他格式尝试使用默认解析
				else {
					return LocalDateTime.parse(source);
				}
			}
		});

		// LocalDate 转换器
		registry.addConverter(new Converter<String, LocalDate>() {
			@Override
			public LocalDate convert(String source) {
				if (source == null || source.trim().isEmpty()) {
					return null;
				}
				source = source.trim();

				// 支持格式: yyyy-MM-dd
				if (source.matches("^\\d{4}-\\d{1,2}-\\d{1,2}$")) {
					return LocalDate.parse(source, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
				}
				// 其他格式尝试使用默认解析
				else {
					return LocalDate.parse(source);
				}
			}
		});

		// LocalTime 转换器
		registry.addConverter(new Converter<String, LocalTime>() {
			@Override
			public LocalTime convert(String source) {
				if (source == null || source.trim().isEmpty()) {
					return null;
				}
				source = source.trim();

				// 支持格式: HH:mm:ss
				if (source.matches("^\\d{1,2}:\\d{1,2}:\\d{1,2}$")) {
					return LocalTime.parse(source, DateTimeFormatter.ofPattern("HH:mm:ss"));
				}
				// 支持格式: HH:mm
				else if (source.matches("^\\d{1,2}:\\d{1,2}$")) {
					return LocalTime.parse(source, DateTimeFormatter.ofPattern("HH:mm"));
				}
				// 其他格式尝试使用默认解析
				else {
					return LocalTime.parse(source);
				}
			}
		});
	}
}
