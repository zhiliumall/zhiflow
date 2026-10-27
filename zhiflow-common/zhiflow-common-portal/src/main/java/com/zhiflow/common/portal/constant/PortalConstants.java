/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.portal.constant;

/**
 */
public interface PortalConstants {
	/**
	 * header中的tenant-id
	 */
	String HEADER_TENANT_ID = "tenant-id";
	/**
	 * header中的app-id
	 */
	String HEADER_APP_ID = "app-id";
	/**
	 * header中的third-session
	 */
	String HEADER_THIRDSESSION = "third-session";
	/**
	 * header中的客户端类型
	 */
	String HEADER_CLIENT_TYPE = "client-type";
	/**
	 * 客户端类型：小程序，普通H5，微信H5，APPapp，pcH5
	 */
	String CLIENT_TYPE_MA = "MA";
	String CLIENT_TYPE_H5 = "H5";
	String CLIENT_TYPE_H5_WX = "H5-WX";
	String CLIENT_TYPE_H5_PC = "H5-PC";
	String CLIENT_TYPE_APP = "APP";
}
