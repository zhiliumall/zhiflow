/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.portal.util;

import com.zhiflow.common.portal.constant.PortalConstants;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Api工具类
 */
public class ApiUtil {
	/**
	 * 通过request获取appId
	 * @param request
	 * @return
	 * @throws Exception
	 */
	public static String getAppId(HttpServletRequest request) {
		String appId = request.getHeader(PortalConstants.HEADER_APP_ID);
		return appId;
	}

	/**
	 * 通过request获取clientType
	 * @param request
	 * @return
	 * @throws Exception
	 */
	public static String getClientType(HttpServletRequest request) {
		String clientType = request.getHeader(PortalConstants.HEADER_CLIENT_TYPE);
		return clientType;
	}
}
