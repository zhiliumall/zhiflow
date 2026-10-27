package com.zhiflow.common.log.util;

import com.zhiflow.common.log.config.BaseLogProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 入参采集与脱敏回归。
 *
 * <p>重点是 <b>Map 入参的掩码</b>：Jackson 的 {@code SimpleBeanPropertyFilter} 只作用于
 * bean 属性，对 {@code Map} 无效。本项目有大量 Map 入参接口（{@code AiHealthController.saveProfile(Map)}、
 * {@code BankController.ocr(Map)} 等），若沿用 filter 方案，这些接口的敏感字段会原文落库。
 */
class SysLogParamsCollectorTest {

	private BaseLogProperties properties;

	private MockHttpServletRequest request;

	@BeforeEach
	void setUp() {
		properties = new BaseLogProperties();
		properties.setExcludeFields(List.of("password", "mobile", "idcard", "phone"));
		request = new MockHttpServletRequest("POST", "/test");
	}

	@Test
	@DisplayName("Map 入参：敏感字段被掩码（filter 方案在此失效）")
	void mapBodyIsMasked() {
		Map<String, Object> body = Map.of(
				"userName", "张三",
				"mobile", "13800001111",
				"idcard", "310101199001011234");

		String params = collect("saveProfile", new Object[] { body });

		assertTrue(params.contains("张三"));
		assertFalse(params.contains("13800001111"), "手机号不得原文落库：" + params);
		assertFalse(params.contains("310101199001011234"), "身份证不得原文落库：" + params);
	}

	@Test
	@DisplayName("名字里带 password 的字段都打码（改密请求的 newpassword1 / newPassword 曾以明文落进 sys_log）")
	void anyPasswordNamedFieldIsMasked() {
		Map<String, Object> body = Map.of(
				"username", "admin",
				"password", "Old-Pass-123",
				"newpassword1", "New-Pass-456",
				"newPassword", "New-Pass-789");

		String params = collect("saveProfile", new Object[] { body });

		assertTrue(params.contains("admin"), "对照：普通字段照常记录：" + params);
		assertFalse(params.contains("Old-Pass-123"), params);
		assertFalse(params.contains("New-Pass-456"), "newpassword1 不得原文落库：" + params);
		assertFalse(params.contains("New-Pass-789"), "newPassword 不得原文落库：" + params);
	}

	@Test
	@DisplayName("嵌套对象里的敏感字段也被掩码")
	void nestedSensitiveFieldsAreMasked() {
		Map<String, Object> body = Map.of(
				"orderNo", "SO123",
				"buyer", Map.of("name", "李四", "phone", "13900002222"));

		String params = collect("saveProfile", new Object[] { body });

		assertTrue(params.contains("SO123"));
		assertTrue(params.contains("李四"));
		assertFalse(params.contains("13900002222"), "嵌套手机号不得原文落库：" + params);
	}

	@Test
	@DisplayName("数组元素里的敏感字段也被掩码")
	void sensitiveFieldsInsideArraysAreMasked() {
		Map<String, Object> body = Map.of("contacts", List.of(
				Map.of("name", "王五", "mobile", "13700003333"),
				Map.of("name", "赵六", "mobile", "13600004444")));

		String params = collect("saveProfile", new Object[] { body });

		assertTrue(params.contains("王五"));
		assertFalse(params.contains("13700003333"), "数组内手机号不得原文落库：" + params);
		assertFalse(params.contains("13600004444"), "数组内手机号不得原文落库：" + params);
	}

	@Test
	@DisplayName("字段名大小写不敏感")
	void maskingIsCaseInsensitive() {
		Map<String, Object> body = Map.of("Mobile", "13800001111", "PassWord", "secret");

		String params = collect("saveProfile", new Object[] { body });

		assertFalse(params.contains("13800001111"), params);
		assertFalse(params.contains("secret"), params);
	}

	@Test
	@DisplayName("query 参数中的敏感字段被掩码")
	void queryParamsAreMasked() {
		request.addParameter("keyword", "感冒药");
		request.addParameter("phone", "13800001111");

		String params = collect("saveProfile", new Object[] { Map.of() });

		assertTrue(params.contains("感冒药"));
		assertFalse(params.contains("13800001111"), params);
	}

	@Test
	@DisplayName("超长 params 按 maxLength 截断")
	void oversizeParamsAreTruncated() {
		properties.setMaxLength(200);
		Map<String, Object> body = Map.of("note", "x".repeat(5000));

		String params = collect("saveProfile", new Object[] { body });

		assertTrue(params.length() <= 200, "实际长度 " + params.length());
		assertTrue(params.endsWith("...(truncated)"), params);
	}

	@Test
	@DisplayName("跳过 Servlet 容器对象，不做序列化")
	void servletTypesAreSkipped() {
		String params = collect("download", new Object[] { "SO123", new MockHttpServletResponse() });

		assertTrue(params.contains("SO123"));
		assertFalse(params.contains("mockHttpServletResponse"), params);
		// 不应因序列化容器对象而失败
		assertFalse(params.contains("_collectError"), params);
	}

	@Test
	@DisplayName("无参无 query 时产出空 JSON 对象而非 null")
	void emptyParamsProduceEmptyJson() {
		String params = collect("noArgs", new Object[] {});

		assertEquals("{}", params);
	}

	@Test
	@DisplayName("敏感命令可保留 query 元数据同时完全跳过 request body")
	void sensitiveCommandCanOptOutOfBodyCapture() {
		request.addParameter("operation", "balance-adjust");
		String params = collect("saveProfile", new Object[] { Map.of("reason", "患者专属调账说明", "phone", "13800001111") }, false);

		assertTrue(params.contains("balance-adjust"), params);
		assertFalse(params.contains("患者专属调账说明"), params);
		assertFalse(params.contains("13800001111"), params);
	}

	@Test
	@DisplayName("带 LocalDateTime 的实体入参：body 正常落库，不退化成 _collectError")
	void javaTimeFieldsDoNotBreakBodyCapture() {
		// 2026-07-30 回归：裸 ObjectMapper 在此抛「Java 8 date/time type not supported」，
		// 异常被外层 catch 吞掉后整条 params 只剩 _collectError，「改了什么」无从追查。
		Stub.PageLike body = new Stub.PageLike("1900000000000000003", "智流首页",
				LocalDateTime.of(2026, 7, 30, 18, 19, 34));

		String params = collect("savePage", new Object[] { body });

		assertFalse(params.contains("_collectError"), "body 不应因时间字段而整体丢失：" + params);
		assertFalse(params.contains("_bodyError"), params);
		assertTrue(params.contains("1900000000000000003"), "必须能看出改的是哪条记录：" + params);
		assertTrue(params.contains("2026-07-30 18:19:34"), "时间应按 yyyy-MM-dd HH:mm:ss 落库：" + params);
	}

	@Test
	@DisplayName("body 序列化失败不连坐：已采到的 path 变量仍然保留")
	void bodyFailureKeepsPathVariables() {
		String params = collect("savePageWithPath",
				new Object[] { "SO123", new Stub.Unserializable() });

		assertTrue(params.contains("SO123"), "path 变量不应被 body 失败带走：" + params);
		assertTrue(params.contains("_bodyError"), "body 失败应留下可诊断标记：" + params);
	}

	// ---------- helpers ----------

	private String collect(String methodName, Object[] args) {
		return collect(methodName, args, true);
	}

	private String collect(String methodName, Object[] args, boolean captureBody) {
		ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
		MethodSignature signature = mock(MethodSignature.class);
		Method method = findMethod(methodName);

		when(point.getSignature()).thenReturn(signature);
		when(signature.getMethod()).thenReturn(method);
		when(signature.getParameterNames()).thenReturn(new String[] { "orderNo", "response" });
		when(point.getArgs()).thenReturn(args);

		return SysLogParamsCollector.collect(point, request, properties, captureBody);
	}

	private Method findMethod(String name) {
		for (Method method : Stub.class.getDeclaredMethods()) {
			if (method.getName().equals(name)) {
				return method;
			}
		}
		throw new IllegalStateException("测试桩方法不存在: " + name);
	}

	static class Stub {

		public String saveProfile(@RequestBody Map<String, Object> body) {
			return "ok";
		}

		public void download(@PathVariable("orderNo") String orderNo, HttpServletResponse response) {
		}

		public String noArgs() {
			return "ok";
		}

		public String savePage(@RequestBody PageLike body) {
			return "ok";
		}

		public String savePageWithPath(@PathVariable("orderNo") String orderNo, @RequestBody Unserializable body) {
			return "ok";
		}

		/** 仿装修页实体：id + 名称 + LocalDateTime，复现真实入参形状 */
		record PageLike(String id, String pageName, LocalDateTime updateTime) {
		}

		/** 无属性且禁止序列化，用于触发 body 序列化失败 */
		static class Unserializable {

			@Override
			public String toString() {
				return "unserializable";
			}

		}
	}
}
