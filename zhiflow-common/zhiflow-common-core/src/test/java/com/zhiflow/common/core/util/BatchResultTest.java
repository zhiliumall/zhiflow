package com.zhiflow.common.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchResultTest {

	@Test
	void partialSuccess_countsAndDetailsAreConsistent() {
		BatchResult<String> result = BatchResult.of(3);
		result.succeed("a").fail("b", "STOCK_LOCKED", "库存被锁定").succeed("c");

		assertEquals(3, result.getTotal());
		assertEquals(2, result.getSuccessCount());
		assertEquals(1, result.getFailCount());
		assertEquals("b", result.getFailed().getFirst().getId());
		assertEquals("STOCK_LOCKED", result.getFailed().getFirst().getCode());
		assertFalse(result.isUnaccounted(), "三条全部有归属，不应判为静默跳过");
	}

	@Test
	void allFailed_isStillAValidResult() {
		BatchResult<Long> result = BatchResult.of(2);
		result.fail(1L, "NOT_FOUND", "记录不存在").fail(2L, "NO_PERMISSION", "无权限");

		assertEquals(0, result.getSuccessCount());
		assertEquals(2, result.getFailCount());
		assertFalse(result.isUnaccounted());
	}

	@Test
	void silentlySkippedItem_isDetected() {
		// 声明 3 条但只处理了 2 条——批量循环里存在静默跳过分支
		BatchResult<String> result = BatchResult.of(3);
		result.succeed("a").fail("b", "E", "r");

		assertTrue(result.isUnaccounted(), "未处理条目必须能被检出，否则前端会把丢失当成功");
	}

	@Test
	void emptyResult_hasNoUnaccountedFalsePositive() {
		BatchResult<String> result = BatchResult.of(0);

		assertEquals(0, result.getTotal());
		assertTrue(result.getSucceeded().isEmpty());
		assertTrue(result.getFailed().isEmpty());
		assertFalse(result.isUnaccounted(), "空批次不应判为静默跳过");
	}

	@Test
	void deserializedShape_toleratesNullCollections() {
		// Jackson 反序列化缺字段时集合可能为 null，getter 须兜住
		BatchResult<String> result = new BatchResult<>();
		result.setSucceeded(null);
		result.setFailed(null);

		assertEquals(0, result.getSuccessCount());
		assertEquals(0, result.getFailCount());
		assertTrue(result.getSucceeded().isEmpty());
	}
}
