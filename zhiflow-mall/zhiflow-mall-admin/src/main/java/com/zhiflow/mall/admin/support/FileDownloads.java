package com.zhiflow.mall.admin.support;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 后台文件下载响应（导出、模板）。文件名同时写 {@code filename} 与 RFC 5987 的 {@code filename*}，中文名在各浏览器都不乱码。
 */
public final class FileDownloads {

	private FileDownloads() {
	}

	public static ResponseEntity<byte[]> attachment(byte[] bytes, String fileName) {
		String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
				.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
				.body(bytes);
	}
}
