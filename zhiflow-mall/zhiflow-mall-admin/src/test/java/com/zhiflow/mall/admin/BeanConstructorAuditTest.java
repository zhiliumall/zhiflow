package com.zhiflow.mall.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静态审计：每个 Spring 组件类都必须有**唯一确定**的注入构造器。
 *
 * <p>存在的理由是 2026-07-29 那次事故：从主干构建的 mall-admin 启动连崩两轮，
 * 第一轮是 {@code LogisticsAccountKeyProvider} 没有实现类，第二轮是
 * {@code BusinessStatisticsClock} 有两个构造器且都没标 {@code @Autowired}
 * （Spring 遇到多构造器且无标注时会回退去找无参构造器，找不到就报
 * {@code No default constructor found}）。两轮都是**编译通过、单测通过、一上生产就崩**，
 * 每次都要靠回滚恢复。这类缺陷不该用生产发版来发现。
 *
 * <p>判据照抄 Spring 的构造器决议顺序：
 * <ol>
 *   <li>只有一个声明构造器 → 用它；</li>
 *   <li>否则恰好一个标了 {@code @Autowired} → 用它；</li>
 *   <li>否则回退无参构造器；没有就无法实例化。</li>
 * </ol>
 *
 * <p>本测试不启动 Spring 上下文、不连数据库，纯类路径扫描 + 反射，可在任何环境跑。
 */
class BeanConstructorAuditTest {

    private static final String BASE_PACKAGE = "com.zhiflow.mall.admin";

    @Test
    @DisplayName("所有 Spring 组件类的注入构造器都可被唯一决议")
    void everyComponentHasResolvableConstructor() {
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
                        // 类加载不了本身就是问题，但归属另一类（缺依赖），这里只记不判
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
                "以下组件类的构造器无法被 Spring 唯一决议，上生产会在启动期崩溃：\n  "
                        + String.join("\n  ", violations));
    }

    /** 返回问题描述，可正常决议时返回 null。 */
    private static String constructorProblem(Class<?> type) {
        Constructor<?>[] declared = type.getDeclaredConstructors();
        if (declared.length <= 1) {
            return null;
        }
        long annotated = Arrays.stream(declared)
                .filter(c -> c.isAnnotationPresent(Autowired.class))
                .count();
        if (annotated == 1) {
            return null;
        }
        if (annotated > 1) {
            return "有 " + annotated + " 个构造器标了 @Autowired，Spring 无法选择";
        }
        boolean hasNoArg = Arrays.stream(declared).anyMatch(c -> c.getParameterCount() == 0);
        if (hasNoArg) {
            return null;
        }
        return "有 " + declared.length + " 个构造器、都没标 @Autowired、且没有无参构造器"
                + "（Spring 会回退找无参构造器并报 No default constructor found）";
    }
}
