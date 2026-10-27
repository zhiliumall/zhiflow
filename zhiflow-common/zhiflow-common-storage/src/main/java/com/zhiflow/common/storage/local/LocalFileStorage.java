/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.storage.local;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 把上传文件存到本地目录，返回访问地址 {@code {urlPrefix}/{dir}{uuid}.{扩展名}}。
 *
 * <p>文件名一律用 UUID，只保留校验过的扩展名；目录段只许字母、数字、下划线、中划线与斜杠，最终路径必须落在根目录之内。
 */
public class LocalFileStorage {

	private static final Pattern DIR = Pattern.compile("[A-Za-z0-9_\\-/]*");

	private static final Pattern EXTENSION = Pattern.compile("[a-z0-9]{1,10}");

	private final Path root;

	private final String urlPrefix;

	public LocalFileStorage(LocalStorageProperties properties) {
		this.root = Path.of(properties.getDir()).toAbsolutePath().normalize();
		String prefix = properties.getUrlPrefix() == null ? "" : properties.getUrlPrefix().trim();
		while (prefix.endsWith("/")) {
			prefix = prefix.substring(0, prefix.length() - 1);
		}
		this.urlPrefix = prefix;
	}

	public Path root() {
		return root;
	}

	/**
	 * @param file 已落到临时目录的上传文件
	 * @param dir  调用方给的子目录（如 {@code 1/goods/}），可空
	 * @return 访问地址
	 */
	public String save(File file, String dir) {
		String subDir = dir == null ? "" : dir.replace('\\', '/');
		if (!DIR.matcher(subDir).matches() || subDir.contains("..")) {
			throw new IllegalArgumentException("存储目录不合法：" + dir);
		}
		while (subDir.startsWith("/")) {
			subDir = subDir.substring(1);
		}
		if (!subDir.isEmpty() && !subDir.endsWith("/")) {
			subDir = subDir + "/";
		}
		String name = UUID.randomUUID() + extensionOf(file.getName());
		Path target = root.resolve(subDir + name).normalize();
		if (!target.startsWith(root)) {
			throw new IllegalArgumentException("存储目录不合法：" + dir);
		}
		try {
			Files.createDirectories(target.getParent());
			Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e) {
			throw new UncheckedIOException("本地存储写入失败：" + target, e);
		}
		return urlPrefix + "/" + subDir + name;
	}

	static String extensionOf(String fileName) {
		int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
		if (dot < 0) {
			return "";
		}
		String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
		return EXTENSION.matcher(ext).matches() ? "." + ext : "";
	}
}
