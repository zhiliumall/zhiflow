/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.pay.admin.config;

import cn.hutool.core.util.StrUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * pay_config 装配指纹：对参与支付服务装配的字段与证书文件 stat 求哈希，作本地单例缓存 key 的一段。
 * admin 改配置 → findByType 缓存被 evict → 下次读到新值 → 指纹变 → 换 key 重建实例（指纹自失效，无需清理钩子）。
 *
 */
final class PayConfigFingerprint {

	private PayConfigFingerprint() {
	}

	/**
	 * 各段以 \u0001 分隔、null 记 \u0002，避免相邻字段拼接歧义
	 */
	static String of(String... parts) {
		StringBuilder joined = new StringBuilder();
		for (String part : parts) {
			joined.append(part == null ? "\u0002" : part).append('\u0001');
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(joined.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * 证书文件维度：mtime+size 进指纹，覆盖「内容变、路径不变」的换证书场景；
	 * 非文件系统路径（classpath: 等）无 stat，只能靠路径本身区分（进程重启缓存本就清空）
	 */
	static String fileStat(String path) {
		if (StrUtil.isBlank(path)) {
			return "-";
		}
		File file = new File(path);
		return file.isFile() ? file.lastModified() + ":" + file.length() : "na";
	}
}
