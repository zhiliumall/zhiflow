/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.storage.util;

import com.zhiflow.upms.common.dto.StorageCredentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开源版只认本地（0）与 MinIO（3）：已删的阿里云 OSS / 七牛 / 腾讯 COS 配置要报清楚，而不是静默返回空地址。
 */
class UploadFileUtilsTypeTest {

	@Test
	@DisplayName("已删的存储类型（1 阿里云 / 2 七牛 / 4 腾讯）明确报错")
	void removedTypesFailLoudly() {
		for (String type : new String[] { "1", "2", "4" }) {
			StorageCredentials c = new StorageCredentials();
			c.setStorageType(type);
			IllegalStateException e = assertThrows(IllegalStateException.class,
					() -> UploadFileUtils.uploadFile(new File("x.png"), "1/", c));
			assertTrue(e.getMessage().contains("本地（0）与 MinIO（3）"), e.getMessage());
		}
	}
}
