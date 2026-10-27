/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.storage.util;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.core.util.SpringContextHolder;
import com.zhiflow.common.storage.entity.StorageConfig;
import com.zhiflow.common.storage.local.LocalFileStorage;
import com.zhiflow.upms.common.dto.StorageCredentials;

import java.io.File;

/**
 * 文件上传：开源版只有两种存储（方案 H-10 / K-M16）——
 * <ul>
 *   <li>本地（{@link #LOCAL}，也是没有存储配置时的默认）：落应用服务器本地目录，见 {@link LocalFileStorage}；</li>
 *   <li>MinIO（{@link #MINIO}）：按后台「文件存储配置」的 endpoint / bucket / 密钥上传。</li>
 * </ul>
 */
public class UploadFileUtils {

	public static final String LOCAL = "0";

	public static final String MINIO = "3";

	private UploadFileUtils() {
	}

	/**
	 * @param file               已落到临时目录的上传文件
	 * @param dir                子目录（如 {@code 1/goods/}）
	 * @param storageCredentials 存储配置；为空 = 没配过，按本地存
	 * @return 访问地址
	 */
	public static String uploadFile(File file, String dir, StorageCredentials storageCredentials) {
		String type = storageCredentials == null ? LOCAL : StrUtil.blankToDefault(storageCredentials.getStorageType(), LOCAL);
		if (LOCAL.equals(type)) {
			return local().save(file, dir);
		}
		if (!MINIO.equals(type)) {
			throw new IllegalStateException("不支持的存储类型 " + type + "：开源版只有本地（0）与 MinIO（3），请在后台「文件存储配置」改选");
		}
		try {
			StorageConfig storageConfig = new StorageConfig();
			storageConfig.setAccessKeyId(storageCredentials.getAccessKeyId());
			storageConfig.setAccessKeySecret(storageCredentials.getAccessKeySecret());
			storageConfig.setEndpoint(storageCredentials.getEndpoint());
			storageConfig.setBucket(storageCredentials.getBucket());
			return new MinioUtils(storageConfig).uploadFile(file, dir);
		}
		catch (Exception e) {
			throw new RuntimeException("请检查存储配置是否正确：" + e);
		}
	}

	private static LocalFileStorage local() {
		return SpringContextHolder.getBean(LocalFileStorage.class);
	}
}
