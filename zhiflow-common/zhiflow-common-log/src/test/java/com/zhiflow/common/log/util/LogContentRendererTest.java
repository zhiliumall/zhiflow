package com.zhiflow.common.log.util;

import com.zhiflow.common.log.context.LogRecordContext;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S4 操作日志内容模板渲染单测。
 *
 * <p>这个类的价值全在「渲染失败必须降级」那几条上——模板是字符串，编译器不管，
 * 写错是必然会发生的，而一次日志渲染绝不能让删除操作失败。
 */
class LogContentRendererTest {

	@AfterEach
	void tearDown() {
		LogRecordContext.clear();
	}

	// ---------- 正常渲染 ----------

	/** 本任务的目标形态：从「删除电子券 + 一个 id」变成人话 */
	@Test
	@DisplayName("LogRecordContext 里的对象可用，产出目标形态的人话")
	void rendersHumanReadableContentFromContextObjects() {
		LogRecordContext.put("coupon", new Coupon("双十一满减"));
		LogRecordContext.put("impact", new Impact(1910L));

		String content = LogContentRenderer.render(
				"'删除优惠券模板「' + #coupon.name + '」，连带作废已发放 ' + #impact.total + ' 张'",
				point("removeById", new Object[] { "c-1" }), true, null);

		assertEquals("删除优惠券模板「双十一满减」，连带作废已发放 1910 张", content);
	}

	/** DELETE /{id} 只有 id 时，方法入参也能取到 */
	@Test
	@DisplayName("方法入参按参数名可用")
	void methodArgumentsAreAvailableByName() {
		String content = LogContentRenderer.render("'删除 id=' + #id",
				point("removeById", new Object[] { "c-9" }), true, null);

		assertEquals("删除 id=c-9", content);
	}

	/** 对齐 yudao 的约定变量 */
	@Test
	@DisplayName("#_ret 与 #_errorMsg 可用")
	void conventionVariablesAreAvailable() {
		assertEquals("结果=true", LogContentRenderer.render("'结果=' + #_ret",
				point("removeById", new Object[] { "x" }), true, null));

		assertEquals("失败：库存不足", LogContentRenderer.render("'失败：' + #_errorMsg",
				point("removeById", new Object[] { "x" }), null, "库存不足"));
	}

	/** 同名时上下文覆盖入参——业务塞的对象比裸 id 有用 */
	@Test
	@DisplayName("LogRecordContext 覆盖同名方法入参")
	void contextOverridesSameNamedArgument() {
		LogRecordContext.put("id", "被覆盖后的值");

		assertEquals("被覆盖后的值", LogContentRenderer.render("#id",
				point("removeById", new Object[] { "原始入参" }), null, null));
	}

	// ---------- 降级（本类的重点） ----------

	@Test
	@DisplayName("未配模板返回 null，行为与改造前一致")
	void blankTemplateReturnsNull() {
		assertNull(LogContentRenderer.render(null, point("removeById", new Object[] { "x" }), null, null));
		assertNull(LogContentRenderer.render("", point("removeById", new Object[] { "x" }), null, null));
		assertNull(LogContentRenderer.render("   ", point("removeById", new Object[] { "x" }), null, null));
	}

	/** 字段名拼错——最常见的模板错误 */
	@Test
	@DisplayName("模板引用不存在的属性 → 降级返回 null，不抛异常")
	void unknownPropertyDegradesToNull() {
		LogRecordContext.put("coupon", new Coupon("双十一"));

		assertNull(LogContentRenderer.render("#coupon.nameTypo",
				point("removeById", new Object[] { "x" }), null, null));
	}

	/** 业务忘了 put，或对象本身查出来是 null */
	@Test
	@DisplayName("模板引用未放入的变量 → 降级返回 null，不抛异常")
	void missingVariableDegradesToNull() {
		assertNull(LogContentRenderer.render("#从来没放过的东西.name",
				point("removeById", new Object[] { "x" }), null, null));
	}

	@Test
	@DisplayName("语法错误的模板 → 降级返回 null，不抛异常")
	void malformedTemplateDegradesToNull() {
		assertNull(LogContentRenderer.render("'未闭合的字符串",
				point("removeById", new Object[] { "x" }), null, null));
	}

	/** 防止模板拼出超长文本撑爆 content 列 */
	@Test
	@DisplayName("渲染结果超长时截断到 1000")
	void overlongResultIsTruncated() {
		LogRecordContext.put("long", "x".repeat(5000));

		String content = LogContentRenderer.render("#long", point("removeById", new Object[] { "x" }), null, null);

		assertEquals(1000, content.length());
	}

	// ---------- LogRecordContext 自身 ----------

	@Test
	@DisplayName("clear 后变量不残留（线程复用防串）")
	void clearRemovesVariables() {
		LogRecordContext.put("a", 1);
		assertEquals(1, LogRecordContext.getAll().size());

		LogRecordContext.clear();

		assertTrue(LogRecordContext.getAll().isEmpty());
	}

	@Test
	@DisplayName("未放入任何变量时 getAll 返回空 Map 而不是 null")
	void getAllNeverReturnsNull() {
		assertTrue(LogRecordContext.getAll().isEmpty());
	}

	// ---------- helpers ----------

	private static ProceedingJoinPoint point(String methodName, Object[] args) {
		try {
			Method method = Sample.class.getDeclaredMethod(methodName, String.class);
			MethodSignature signature = mock(MethodSignature.class);
			when(signature.getMethod()).thenReturn(method);
			ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
			when(point.getSignature()).thenReturn(signature);
			when(point.getArgs()).thenReturn(args);
			return point;
		}
		catch (NoSuchMethodException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 提供一个真实方法给 ParameterNameDiscoverer 解析参数名 */
	@SuppressWarnings("unused")
	static class Sample {

		public void removeById(String id) {
		}
	}

	@Data
	@AllArgsConstructor
	static class Coupon {

		private String name;
	}

	@Data
	@AllArgsConstructor
	static class Impact {

		private Long total;
	}
}
