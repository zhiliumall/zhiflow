/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.storage.util;

import java.util.Locale;
import java.util.Set;

/**
 * 直链访问时浏览器会当「可执行页面」渲染的扩展名：存储一律让它们以 {@code Content-Disposition: attachment} 返回。
 *
 * <p>上传口对 html / svg / js 不拒收（后台知识库文档要用，见 {@code UploadValidator} 的说明），所以存储型 XSS
 * 只能在「怎么返回」这一侧挡：带 attachment 后点直链变下载，脚本不执行；页面里 {@code <img src="...svg">} 照常显示。
 * 刻意不含 pdf：浏览器用沙箱化的查看器渲染，不是同源脚本执行面，且有内联预览需要。
 */
public final class InlineUnsafeTypes {

	private static final Set<String> SUFFIXES = Set.of(
			"svg", "svgz", "html", "htm", "xhtml", "shtml", "xml", "js", "mjs", "htc", "swf");

	private InlineUnsafeTypes() {
	}

	/** @param filename 文件名或路径（按最后一个点取扩展名） */
	public static boolean isUnsafe(String filename) {
		if (filename == null) {
			return false;
		}
		int dot = filename.lastIndexOf('.');
		return dot >= 0 && SUFFIXES.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
	}
}
