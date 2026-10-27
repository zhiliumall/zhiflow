package com.zhiflow.mall.api.controller;

import com.zhiflow.common.core.config.JacksonConfig;
import com.zhiflow.common.data.resolver.SqlFilterArgumentResolver;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 测试用：standalone MockMvc + <b>应用真实的 JSON 配置</b>（{@link JacksonConfig} 的定制器，同 Boot 装配路径），
 * 以及 C 端分页参数解析器。用来断言「响应体里到底有哪些键」——这是数据收敛类修复唯一可信的判据：
 * 只看 Java 对象会漏掉 Jackson 的派生 getter、null 键、子类注解是否生效。
 */
final class AppJsonForTest {

	static final JsonMapper MAPPER = build();

	private AppJsonForTest() {
	}

	private static JsonMapper build() {
		JsonMapper.Builder builder = JsonMapper.builder();
		new JacksonConfig().customizer().customize(builder);
		return builder.build();
	}

	static MockMvc mvc(Object controller) {
		return MockMvcBuilders.standaloneSetup(controller)
				.setCustomArgumentResolvers(new SqlFilterArgumentResolver())
				.setMessageConverters(new JacksonJsonHttpMessageConverter(MAPPER))
				.build();
	}

	static String body(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> json(MvcResult result) throws Exception {
		return MAPPER.readValue(body(result), Map.class);
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> map(Object node) {
		return (Map<String, Object>) node;
	}

	@SuppressWarnings("unchecked")
	static List<Object> list(Object node) {
		return (List<Object>) node;
	}
}
