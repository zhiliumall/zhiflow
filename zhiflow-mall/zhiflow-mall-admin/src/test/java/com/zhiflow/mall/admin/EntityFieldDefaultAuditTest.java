package com.zhiflow.mall.admin;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静态审计：{@code @TableName} 实体上不允许写字段初值（也不允许用基本类型）。
 *
 * <p>存在的理由是 2026-09-07 那次事故。{@code GoodsSpu} 上写了一行
 * {@code private Boolean isDistribution = false;}，看起来只是个默认值，但实体在本仓里同时承担
 * 三种角色，而 MyBatis-Plus 对这三种角色都是「非 null 即生效」：
 *
 * <ol>
 *   <li><b>整行更新</b>——{@code updateById(entity)}；</li>
 *   <li><b>局部 patch</b>——{@code new GoodsSpu()} 只 set 想改的两三列，再交给
 *       {@code update(entity, wrapper)} / {@code updateById(patch)}；</li>
 *   <li><b>条件查询</b>——{@code Wrappers.query(entity)}，非 null 字段变成 {@code AND col = ?}。</li>
 * </ol>
 *
 * <p>于是那行字段初值让四个调用点集体走样：商品审核、商品上下架、导入改价、推广备注
 * 每次都顺手写一次 {@code is_distribution = false}，把商品静默踢出推广池；
 * {@code /goodsspu/count} 和 {@code /goodsspu/statistics} 则凭空多出
 * {@code AND is_distribution = false}——租户 1 有 452 个在架商品，两个接口常年只报 24。
 * 运营侧看到的现象是「后台把『参与推广』打开，保存也提示成功，一审核就又变回关闭」，
 * 前后端各查各的都查不出问题。
 *
 * <p>基本类型（{@code boolean} / {@code int} …）同理：它们永远非 null，等价于一个永久生效的
 * 字段初值，所以一并禁掉。新增时确实需要的默认值，请写在 service 的 insert 路径上
 * （例：{@code GoodsSpuServiceImpl#saveHanddler}），那里只影响新增，不会污染 patch 和查询。
 *
 * <p>{@code @TableField(exist = false)} 的字段不进 SQL，不在审计范围内。
 *
 * <p>本测试不启动 Spring 上下文、不连数据库，纯类路径扫描 + 反射。
 */
class EntityFieldDefaultAuditTest {

    private static final String ENTITY_PACKAGE = "com.zhiflow.mall.common.entity";

    /**
     * 例外名单：这些实体的字段初值只服务于 insert 路径，仓库内没有把它们当 patch 或查询条件用。
     * 想往这里加名字之前，先确认该实体没有任何 {@code new Xxx()} 之后走 update/query 的调用点，
     * 否则加进来等于把同一类事故再放行一次。
     */
    private static final Set<String> ALLOWED = Set.of(
            // 只在 PromoCollegeCourseServiceImpl#addCourse 里 new 后 save()
            "PromoCollegeCourse",
            // 只在 DistributionCourseServiceImpl 里 new 后 insert()
            "PromoCourseProgress",
            // 只在新增素材包时 new 后 save()
            "PromoMaterialPack"
    );

    @Test
    @DisplayName("MyBatis-Plus 实体不得带字段初值或基本类型字段")
    void entitiesMustNotCarryFieldDefaults() throws Exception {
        List<String> violations = new ArrayList<>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(TableName.class));

        for (BeanDefinition definition : scanner.findCandidateComponents(ENTITY_PACKAGE)) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            if (ALLOWED.contains(type.getSimpleName())) {
                continue;
            }
            Object instance;
            try {
                instance = type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                // 没有无参构造器的实体 MyBatis-Plus 本来就用不了，交给别的地方去发现
                continue;
            }
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                // @TableField(exist = false) 不进 SQL，既不会被 patch 写出去，也不会变成查询条件
                TableField mapping = field.getAnnotation(TableField.class);
                if (mapping != null && !mapping.exist()) {
                    continue;
                }
                if (field.getType().isPrimitive()) {
                    violations.add(type.getSimpleName() + "." + field.getName()
                            + " 是基本类型（" + field.getType().getSimpleName() + "），恒非 null");
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(instance);
                if (value != null) {
                    violations.add(type.getSimpleName() + "." + field.getName()
                            + " 带字段初值 " + value);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "实体字段初值会被 MyBatis-Plus 当成调用方的真实意图，污染局部 patch 与条件查询。"
                        + "默认值请挪到 service 的 insert 路径上。违规项：\n  "
                        + String.join("\n  ", violations));
    }
}
