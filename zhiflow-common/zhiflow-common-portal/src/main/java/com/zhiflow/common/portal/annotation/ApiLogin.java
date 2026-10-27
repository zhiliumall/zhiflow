/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.portal.annotation;

import java.lang.annotation.*;

/**
 * 加上此注解，接口必须登录带session才能访问
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiLogin {

	/**
	 * 必须登录才能访问，默认true
	 * @return
	 */
	boolean mustLogin() default true;

}
