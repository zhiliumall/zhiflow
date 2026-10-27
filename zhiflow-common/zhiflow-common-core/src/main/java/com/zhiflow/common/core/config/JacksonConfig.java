package com.zhiflow.common.core.config;

import cn.hutool.core.date.DatePattern;
import com.zhiflow.common.core.jackson.JavaTimeModule;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;

import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.util.Locale;
import java.util.TimeZone;

/**
 * JacksonConfig —— HTTP 出入参的全局序列化配置。
 *
 * <p>Jackson 3 迁移说明（2026-09-12）：本类原来实现的是 Boot 的 Jackson 2 定制接口
 * {@code Jackson2ObjectMapperBuilderCustomizer}，OpenRewrite 只把类型 import 换成了 tools.jackson，
 * 没有换这个接口本身（它不知道 Boot 4 另有 Jackson 3 的对应接口），所以留下了编译错误。
 * 现改为实现 {@link JsonMapperBuilderCustomizer}，方法逐一对应：</p>
 * <ul>
 *   <li>{@code locale(..)}          → {@code defaultLocale(..)}</li>
 *   <li>{@code timeZone(..)}        → {@code defaultTimeZone(..)}</li>
 *   <li>{@code simpleDateFormat(s)} → {@code defaultDateFormat(new SimpleDateFormat(s))}</li>
 *   <li>{@code modules(..)}         → {@code addModule(..)}</li>
 *   <li>{@code postConfigurer(om -> om.coercionConfigDefaults()...)} → {@code withCoercionConfigDefaults(..)}
 *       —— Jackson 3 的 mapper 构建后不可变，强制转换配置必须在 builder 阶段完成</li>
 * </ul>
 */
@Configuration
@ConditionalOnClass(ObjectMapper.class)
@AutoConfigureBefore(JacksonAutoConfiguration.class)
public class JacksonConfig {

	@Bean
	public JsonMapperBuilderCustomizer customizer() {
		return builder -> {
			builder.defaultLocale(Locale.CHINA);
			builder.defaultTimeZone(TimeZone.getTimeZone(ZoneId.systemDefault()));
			builder.defaultDateFormat(new SimpleDateFormat(DatePattern.NORM_DATETIME_PATTERN));
			builder.addModule(new JavaTimeModule());
			// 前端可能传空字符串 "" 给 POJO 类型字段，默认会报错，这里配置为自动转为 null
			builder.withCoercionConfigDefaults(
					cfg -> cfg.setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull));
		};
	}
}
