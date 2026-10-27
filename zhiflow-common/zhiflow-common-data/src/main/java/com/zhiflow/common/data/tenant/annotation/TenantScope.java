package com.zhiflow.common.data.tenant.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 租户上下文切换注解
 * <p>
 * 标注在方法或类上，用于临时切换到指定租户上下文执行
 * 适用于跨租户访问场景，如超级管理员查询特定租户数据
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface TenantScope {
    
    /**
     * 目标租户ID
     *
     * @return 租户ID
     */
    String value();
}
