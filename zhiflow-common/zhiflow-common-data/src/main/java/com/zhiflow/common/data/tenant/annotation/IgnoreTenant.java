package com.zhiflow.common.data.tenant.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 忽略租户过滤注解
 * <p>
 * 标注在方法或类上，用于跳过租户过滤逻辑
 * 适用于超级管理员查询等需要跨租户访问的场景
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface IgnoreTenant {
}
