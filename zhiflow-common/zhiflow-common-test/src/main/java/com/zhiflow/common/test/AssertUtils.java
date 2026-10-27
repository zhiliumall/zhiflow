package com.zhiflow.common.test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Bean 断言（对标 yudao {@code AssertUtils}）。
 *
 * <p><b>用途</b>：逐字段比对两个对象，<b>支持忽略指定字段</b>。
 * 典型场景是断言「保存下去的东西和期望的一致」，但 {@code id} / {@code createTime} /
 * {@code updateTime} 这些由框架填充的字段不可控，得排除掉。
 *
 * <p>比 {@code assertEquals(expected, actual)} 好在两点：不依赖实体正确实现 {@code equals}
 * （本项目实体大量用 Lombok {@code @Data}，继承体系下的 equals 语义容易出意外），
 * 且失败时能一次性报出**所有**不一致的字段，而不是撞到第一个就停。
 */
public final class AssertUtils {

	private AssertUtils() {
	}

	/**
	 * 逐字段比对两个对象。
	 *
	 * @param ignoreFields 要忽略的字段名，如 {@code "id", "createTime"}
	 */
	public static void assertPojoEquals(Object expected, Object actual, String... ignoreFields) {
		if (expected == null || actual == null) {
			if (expected != actual) {
				fail("一方为 null：expected=%s, actual=%s".formatted(expected, actual));
			}
			return;
		}
		Set<String> ignored = Set.of(ignoreFields);
		List<String> mismatches = new ArrayList<>();

		for (Field field : declaredFieldsIncludingSuper(expected.getClass())) {
			if (ignored.contains(field.getName())) {
				continue;
			}
			field.setAccessible(true);
			Object expectedValue = read(field, expected);
			Object actualValue = read(field, actual);
			if (!nullSafeEquals(expectedValue, actualValue)) {
				mismatches.add("  %s: expected=<%s> actual=<%s>"
						.formatted(field.getName(), expectedValue, actualValue));
			}
		}

		if (!mismatches.isEmpty()) {
			fail("%d 个字段不一致：%n%s".formatted(mismatches.size(), String.join("\n", mismatches)));
		}
	}

	/** 沿继承链收集实例字段——本项目实体普遍 extends TenantEntity -> BaseEntity，只看自身类会漏掉 delFlag 等 */
	private static List<Field> declaredFieldsIncludingSuper(Class<?> type) {
		List<Field> fields = new ArrayList<>();
		for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
			for (Field field : current.getDeclaredFields()) {
				if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
					fields.add(field);
				}
			}
		}
		return fields;
	}

	private static Object read(Field field, Object target) {
		try {
			return field.get(target);
		}
		catch (IllegalAccessException e) {
			throw new IllegalStateException("读取字段 " + field.getName() + " 失败", e);
		}
	}

	/** 数组要走 Arrays.equals——本项目有 String[] 字段（goods_spu.label 等），Objects.equals 比的是引用 */
	private static boolean nullSafeEquals(Object a, Object b) {
		if (a instanceof Object[] left && b instanceof Object[] right) {
			return Arrays.equals(left, right);
		}
		return Objects.equals(a, b);
	}
}
