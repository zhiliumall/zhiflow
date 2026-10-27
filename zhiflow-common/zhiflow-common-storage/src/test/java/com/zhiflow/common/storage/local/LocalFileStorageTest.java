/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.storage.local;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地存储：文件落在根目录下、返回 {@code {urlPrefix}/{dir}{uuid}.{扩展名}}；目录穿越与非法目录拒绝；扩展名只留安全字符。
 */
class LocalFileStorageTest {

	@TempDir
	Path tmp;

	private LocalFileStorage storage(String prefix) {
		LocalStorageProperties p = new LocalStorageProperties();
		p.setDir(tmp.resolve("uploads").toString());
		p.setUrlPrefix(prefix);
		return new LocalFileStorage(p);
	}

	private File upload(String name, String content) throws Exception {
		Path f = tmp.resolve(name);
		Files.writeString(f, content);
		return f.toFile();
	}

	@Test
	@DisplayName("存进 {根}/{dir}，返回的地址能对上落盘的文件")
	void savesUnderRootAndReturnsUrl() throws Exception {
		LocalFileStorage s = storage("/files/");
		String url = s.save(upload("a.PNG", "img"), "1/goods");

		assertTrue(url.matches("/files/1/goods/[0-9a-f-]{36}\\.png"), url);
		Path saved = s.root().resolve(url.substring("/files/".length()));
		assertEquals("img", Files.readString(saved));
	}

	@Test
	@DisplayName("目录带 .. 或非法字符一律拒绝，不写任何文件")
	void rejectsTraversal() throws Exception {
		LocalFileStorage s = storage("/files");
		File f = upload("a.png", "x");
		assertThrows(IllegalArgumentException.class, () -> s.save(f, "../etc"));
		assertThrows(IllegalArgumentException.class, () -> s.save(f, "1/goods/../../x"));
		assertThrows(IllegalArgumentException.class, () -> s.save(f, "1/<script>"));
		assertTrue(!Files.exists(s.root()) || Files.list(s.root()).findAny().isEmpty(), "拒绝时不该落任何文件");
	}

	@Test
	@DisplayName("扩展名只留 1～10 位小写字母数字；其余丢掉扩展名")
	void sanitizesExtension() {
		assertEquals(".jpg", LocalFileStorage.extensionOf("x.JPG"));
		assertEquals("", LocalFileStorage.extensionOf("x.p/hp"));
		assertEquals("", LocalFileStorage.extensionOf("noext"));
		assertEquals("", LocalFileStorage.extensionOf("x.verylongextension"));
	}

	@Test
	@DisplayName("地址前缀可写完整地址（前后端不同域）")
	void absoluteUrlPrefix() throws Exception {
		String url = storage("https://api.example.com/files").save(upload("b.webp", "w"), "");
		assertTrue(url.matches("https://api\\.example\\.com/files/[0-9a-f-]{36}\\.webp"), url);
	}
}
