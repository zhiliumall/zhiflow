package com.zhiflow.common.security.annotation;

import java.lang.annotation.*;

/**
 * 服务调用不鉴权注解
 *
 * @author 辰
 * @date 2023年10月26日14:29:51
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Inside {

	/**
	 * 是否AOP统一处理
	 * @return false, true
	 */
	boolean value() default true;

	/**
	 * 需要特殊判空的字段(预留)
	 * @return {}
	 */
	String[] field() default {};

}