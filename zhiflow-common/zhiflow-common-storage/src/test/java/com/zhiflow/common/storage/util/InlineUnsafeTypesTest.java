/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.storage.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineUnsafeTypesTest {

	@Test
	void renderableDocumentsAreUnsafe() {
		assertTrue(InlineUnsafeTypes.isUnsafe("/files/1/goods/a.html"));
		assertTrue(InlineUnsafeTypes.isUnsafe("a.SVG"));
		assertTrue(InlineUnsafeTypes.isUnsafe("1/kb/x.js"));
	}

	@Test
	void mediaAndPdfStayInline() {
		assertFalse(InlineUnsafeTypes.isUnsafe("/files/1/goods/a.png"));
		assertFalse(InlineUnsafeTypes.isUnsafe("a.mp4"));
		assertFalse(InlineUnsafeTypes.isUnsafe("a.pdf"));
		assertFalse(InlineUnsafeTypes.isUnsafe("noext"));
		assertFalse(InlineUnsafeTypes.isUnsafe(null));
	}
}
