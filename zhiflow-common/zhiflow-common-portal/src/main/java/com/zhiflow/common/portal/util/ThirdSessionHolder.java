/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.portal.util;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.zhiflow.common.core.observability.TraceIds;
import com.zhiflow.common.portal.entity.ThirdSession;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * thirdSession工具类
 * <p>
 * set / clear 同步 MDC {@code user.id}（C 端商城用户 id），日志行与报错都能直接对到人。
 */
@Slf4j
public final class ThirdSessionHolder {

	private static final ThreadLocal<ThirdSession> THREAD_LOCAL_THIRD_SESSION = new TransmittableThreadLocal<>();

	private ThirdSessionHolder() {
		throw new IllegalStateException("Utility class");
	}

	/**
	 * TTL 设置thirdSession
	 *
	 * @param thirdSession
	 */
	public static void setThirdSession(ThirdSession thirdSession) {
		THREAD_LOCAL_THIRD_SESSION.set(thirdSession);
		syncMdc(thirdSession == null ? null : thirdSession.getUserId());
	}

	/**
	 * 获取TTL中的thirdSession
	 *
	 * @return
	 */
	public static ThirdSession getThirdSession() {
		ThirdSession thirdSession = THREAD_LOCAL_THIRD_SESSION.get();
		return thirdSession;
	}

	/**
	 * 获取用户商城ID
	 * @return
	 */
	public static String getUserId(){
		ThirdSession thirdSession = getThirdSession();
		if(thirdSession != null){
			return thirdSession.getUserId();
		}
		log.debug("ThirdSession 为空, thread={}", Thread.currentThread().getName());
		return null;
	}
	public static void clear() {
		THREAD_LOCAL_THIRD_SESSION.remove();
		syncMdc(null);
	}

	/** 观测旁路，任何异常都吞掉。 */
	private static void syncMdc(String userId) {
		try {
			if (StringUtils.hasText(userId)) {
				MDC.put(TraceIds.MDC_USER_ID, userId);
			}
			else {
				MDC.remove(TraceIds.MDC_USER_ID);
			}
		}
		catch (RuntimeException e) {
			// 观测代码不打断业务
		}
	}
}
