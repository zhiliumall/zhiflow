package com.zhiflow.upms.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静态审计：upms-admin 的每个 Spring 组件都必须能在启动期被实例化。
 *
 * <p>与 mall-admin 的同名测试同源（那边是 2026-07-29 连崩两轮催生的），本副本多守一条规则，
 * 起因是 <b>2026-08-02 的 upms-admin 崩溃循环</b>：{@code SysUserSecurityController} 用了
 * {@code @AllArgsConstructor}，Lombok 把 {@code @Value} 注解的**非 final** 字段
 * {@code totpEncryptionKey} 也编进了构造器（{@code @Value} 留在字段上、没有复制到构造器参数），
 * Spring 于是去容器里找一个 {@code java.lang.String} bean，找不到就
 * {@code APPLICATION FAILED TO START}。编译通过、单测通过、一上生产就崩，只能回滚。
 *
 * <p>两条规则：
 * <ol>
 *   <li><b>R1 构造器可决议</b>：照抄 Spring 的决议顺序（唯一构造器 → 唯一 {@code @Autowired} → 无参）。</li>
 *   <li><b>R2 注入构造器不得出现"要不到的标量参数"</b>：参数类型是 String/基本类型/包装类/枚举时，
 *       必须带 {@code @Value} 或 {@code @Qualifier}，否则就是按类型去容器里捞标量——必然失败。</li>
 * </ol>
 *
 * <p>不启动 Spring 上下文、不连数据库，纯类路径扫描 + 反射，任何环境可跑。
 */
class BeanConstructorAuditTest {

	private static final String BASE_PACKAGE = "com.zhiflow.upms.admin";

	/** 不可能作为 bean 按类型注入的标量类型（枚举另行判定）。 */
	private static final Set<Class<?>> UNINJECTABLE_SCALARS = Set.of(
			String.class, CharSequence.class,
			boolean.class, byte.class, char.class, short.class, int.class, long.class, float.class, double.class,
			Boolean.class, Byte.class, Character.class, Short.class, Integer.class, Long.class, Float.class, Double.class);

	@Test
	@DisplayName("所有 Spring 组件类的注入构造器都可被唯一决议，且不含要不到的标量参数")
	void everyComponentIsInstantiable() {
		List<String> violations = new ArrayList<>();

		ClassPathScanningCandidateComponentProvider scanner =
				new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

		scanner.findCandidateComponents(BASE_PACKAGE).stream()
				.map(definition -> definition.getBeanClassName())
				.filter(Objects::nonNull)
				.sorted()
				.forEach(className -> {
					Class<?> type;
					try {
						type = Class.forName(className);
					} catch (Throwable e) {
						violations.add(className + " → 无法加载：" + e.getClass().getSimpleName());
						return;
					}
					if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
						return;
					}
					String problem = constructorProblem(type);
					if (problem != null) {
						violations.add(type.getName() + " → " + problem);
					}
				});

		assertTrue(violations.isEmpty(),
				"以下组件类在 Spring 启动期无法实例化，上生产会崩：\n  "
						+ String.join("\n  ", violations));
	}

	/** 返回问题描述，可正常实例化时返回 null。 */
	private static String constructorProblem(Class<?> type) {
		Constructor<?>[] declared = type.getDeclaredConstructors();
		Constructor<?> injected;

		// R1：照抄 Spring 的构造器决议顺序
		if (declared.length == 1) {
			injected = declared[0];
		} else {
			List<Constructor<?>> annotated = Arrays.stream(declared)
					.filter(c -> c.isAnnotationPresent(Autowired.class))
					.toList();
			if (annotated.size() > 1) {
				return "有 " + annotated.size() + " 个构造器标了 @Autowired，Spring 无法选择";
			}
			if (annotated.size() == 1) {
				injected = annotated.get(0);
			} else {
				boolean hasNoArg = Arrays.stream(declared).anyMatch(c -> c.getParameterCount() == 0);
				if (hasNoArg) {
					return null;
				}
				return "有 " + declared.length + " 个构造器、都没标 @Autowired、且没有无参构造器"
						+ "（Spring 会回退找无参构造器并报 No default constructor found）";
			}
		}

		// R2：注入构造器里不得出现「按类型要不到」的标量参数
		for (Parameter parameter : injected.getParameters()) {
			Class<?> paramType = parameter.getType();
			boolean scalar = UNINJECTABLE_SCALARS.contains(paramType) || paramType.isEnum();
			if (!scalar) {
				continue;
			}
			if (parameter.isAnnotationPresent(Value.class) || parameter.isAnnotationPresent(Qualifier.class)) {
				continue;
			}
			return "注入构造器有标量参数 " + paramType.getSimpleName() + " " + parameter.getName()
					+ " 却没带 @Value/@Qualifier —— Spring 会按类型去容器找它并报 "
					+ "No qualifying bean of type '" + paramType.getName() + "'。"
					+ "常见成因：类上用了 @AllArgsConstructor，Lombok 把 @Value 注解的非 final 字段"
					+ "也编进了构造器（注解留在字段上没复制到参数）——改用 @RequiredArgsConstructor";
		}
		return null;
	}
}
