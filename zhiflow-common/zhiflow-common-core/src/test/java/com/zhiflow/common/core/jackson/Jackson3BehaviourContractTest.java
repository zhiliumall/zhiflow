package com.zhiflow.common.core.jackson;

import com.zhiflow.common.core.config.JacksonConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Jackson 2 → Jackson 3 迁移的**行为契约**测试。
 *
 * <p>这四条是迁移里编译器和普通单测都发现不了、但会直接改变对外接口形态的差异。
 * 迁移期间逐条实测确认过等价，这里把结论**钉成测试**，防止将来有人改动序列化配置时
 * 悄悄把它们改回去（那种改动同样是编译过、测试过、上线才出事）。</p>
 *
 * <p>测试用的 mapper 走的是 {@link JacksonConfig} 真实的定制逻辑，不是另起一个裸 mapper，
 * 否则测的就不是实际运行的那一套。</p>
 */
class Jackson3BehaviourContractTest {

	/** 与 Boot 装配同样的路径：把 JacksonConfig 的定制器应用到 JsonMapper.Builder 上。 */
	private static ObjectMapper mapper() {
		JsonMapper.Builder builder = JsonMapper.builder();
		JsonMapperBuilderCustomizer customizer = new JacksonConfig().customizer();
		customizer.customize(builder);
		return builder.build();
	}

	// ---------- 1. null 字段必须照样输出 ----------

	public static class WithNulls {
		public String code = "0";
		public String msg = null;
		public String errCode = null;
	}

	@Test
	@DisplayName("null 字段必须保留在 JSON 里，不能被省略")
	void nullFieldsAreStillWritten() {
		String json = mapper().writeValueAsString(new WithNulls());
		assertTrue(json.contains("\"msg\":null"), "msg 为 null 时必须输出该字段：" + json);
		assertTrue(json.contains("\"errCode\":null"), "errCode 为 null 时必须输出该字段：" + json);
		// 反证：如果哪天有人打开了 NON_NULL，上面两条会失败，而不是悄悄让前端少收到字段
	}

	// ---------- 2. 日期格式必须与迁移前逐字符一致 ----------

	public static class WithDates {
		public LocalDateTime dateTime = LocalDateTime.of(2026, 7, 30, 18, 19, 34);
		public LocalDate date = LocalDate.of(2026, 7, 30);
		public LocalTime time = LocalTime.of(18, 19, 34);
	}

	@Test
	@DisplayName("日期格式：LocalDateTime 必须是 yyyy-MM-dd HH:mm:ss，不能变成时间戳或 ISO-T")
	void dateFormatsUnchanged() {
		String json = mapper().writeValueAsString(new WithDates());
		assertTrue(json.contains("\"dateTime\":\"2026-07-30 18:19:34\""), "日期时间格式变了：" + json);
		assertTrue(json.contains("\"date\":\"2026-07-30\""), "日期格式变了：" + json);
		assertFalse(json.contains("1785"), "日期不能被序列化成时间戳数字：" + json);
		assertFalse(json.contains("2026-07-30T18:19:34"), "不能退化成 ISO 的 T 分隔格式：" + json);
	}

	@Test
	@DisplayName("日期反序列化：yyyy-MM-dd HH:mm:ss 必须能读回来")
	void dateDeserializationUnchanged() {
		WithDates v = mapper().readValue(
				"{\"dateTime\":\"2026-07-30 18:19:34\",\"date\":\"2026-07-30\",\"time\":\"18:19:34\"}",
				WithDates.class);
		assertEquals(LocalDateTime.of(2026, 7, 30, 18, 19, 34), v.dateTime);
		assertEquals(LocalDate.of(2026, 7, 30), v.date);
	}

	// ---------- 3. 未知字段必须继续容忍 ----------

	public static class Small {
		public String name;
	}

	@Test
	@DisplayName("未知字段必须继续容忍，不能因为多一个字段就整个请求 400")
	void unknownPropertiesAreTolerated() {
		Small s = assertDoesNotThrow(() -> mapper().readValue(
				"{\"name\":\"abc\",\"frontendAddedField\":123,\"另一个多余字段\":\"x\"}", Small.class),
				"多余字段不应导致反序列化失败");
		assertEquals("abc", s.name);
	}

	// ---------- 4. 金额精度：numeric(10,2) 的尾零不能丢 ----------

	public static class WithMoney {
		public BigDecimal paymentPrice = new BigDecimal("10.00");
		public BigDecimal freight = new BigDecimal("0.00");
		public BigDecimal big = new BigDecimal("1897.50");
	}

	@Test
	@DisplayName("金额：BigDecimal 的尾零与小数位必须原样输出，不能变成 10.0 / 1.0E1")
	void moneyScaleIsPreserved() {
		String json = mapper().writeValueAsString(new WithMoney());
		assertTrue(json.contains("\"paymentPrice\":10.00"), "10.00 的尾零丢了：" + json);
		assertTrue(json.contains("\"freight\":0.00"), "0.00 的尾零丢了：" + json);
		assertTrue(json.contains("\"big\":1897.50"), "1897.50 被改写了：" + json);
		assertFalse(json.contains("E"), "金额不能被写成科学计数法：" + json);
	}

	// ---------- 5. 空字符串转 null：前端传 "" 给对象字段时的既有约定 ----------

	public static class WithNested {
		public String name;
		public WithMoney nested;
	}

	@Test
	@DisplayName("前端传空字符串给对象字段时，按既有约定转成 null 而不是报错")
	void emptyStringCoercesToNull() {
		WithNested v = assertDoesNotThrow(() -> mapper().readValue(
				"{\"name\":\"abc\",\"nested\":\"\"}", WithNested.class),
				"空字符串应被强制转为 null，而不是抛异常");
		assertNull(v.nested, "nested 应为 null");
		assertEquals("abc", v.name);
	}

	// ---------- 6. 统一返回体 R 的形态：每个接口的响应都套在它外面，形状不能变 ----------

	@Test
	@DisplayName("R.ok(data)：字段集合与 ok 字段必须与迁移前一致")
	void responseWrapperShapeUnchanged() {
		String json = mapper().writeValueAsString(com.zhiflow.common.core.util.R.ok("payload"));
		// 四个显式字段一个都不能少，msg/errCode 为 null 时也要出现
		assertTrue(json.contains("\"code\":0"), "code 变了：" + json);
		assertTrue(json.contains("\"msg\":"), "msg 字段丢了：" + json);
		assertTrue(json.contains("\"errCode\":"), "errCode 字段丢了：" + json);
		assertTrue(json.contains("\"data\":\"payload\""), "data 变了：" + json);
		// ⚠️ isOk() 会被当成 ok 字段序列化出去。R 的类注释明确写了不能给它加 @JsonIgnore
		//（那会从全部 9 个服务的响应里删掉这个字段）。所以这里钉死"它必须还在"。
		assertTrue(json.contains("\"ok\":true"), "isOk() 派生的 ok 字段丢了，前端可能在读它：" + json);
	}

	@Test
	@DisplayName("R.failed(msg)：失败态的形状同样不能变")
	void responseWrapperFailedShapeUnchanged() {
		String json = mapper().writeValueAsString(com.zhiflow.common.core.util.R.failed("出错了"));
		assertTrue(json.contains("\"code\":1"), "失败码变了：" + json);
		assertTrue(json.contains("\"msg\":\"出错了\""), "msg 变了：" + json);
		assertTrue(json.contains("\"ok\":false"), "ok 字段丢了或值不对：" + json);
		assertTrue(json.contains("\"data\":null"), "data 为 null 时仍应输出：" + json);
	}

	@Test
	@DisplayName("R 能读回自己写出的 JSON（服务间调用依赖这条）")
	void responseWrapperRoundTrips() {
		ObjectMapper m = mapper();
		String json = m.writeValueAsString(com.zhiflow.common.core.util.R.ok("payload"));
		// 关键：读回来时 ok 字段是未知属性（R 没有 setOk），必须被 @JsonIgnoreProperties 容忍
		var back = assertDoesNotThrow(() -> m.readValue(json, com.zhiflow.common.core.util.R.class),
				"R 必须能读回自己写出的 JSON —— 服务间 /inside 调用就靠这条");
		assertEquals(0, back.getCode());
	}
}
