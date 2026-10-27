package com.zhiflow.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the monolith/local transport on the same operation-id contract as gRPC COMMAND edges.
 *
 * <p>A local adapter does not receive gRPC metadata, so calling a controller through the old
 * no-argument {@code InternalCallContext.call(...)} silently dropped the order/coupon/IM
 * operation correlation in the single-process assembly.  This gate is transport-level wiring
 * only; business idempotency and status tests remain command-specific.</p>
 */
class CommandLocalOperationIdGateTest {

	private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

	private static final Map<String, List<String>> COMMAND_LOCALS = Map.of(
			"zhiflow-mall/zhiflow-mall-api/src/main/java/com/zhiflow/mall/api/local/OrderInfoLocalApi.java",
			// 其余契约随功能删除，只剩取消订单
			List.of("order-cancel:"));

	@Test
	@DisplayName("COMMAND LocalApi 必须把稳定 operationId 放进 InternalCallContext")
	void commandLocalAdaptersCarryOperationId() {
		for (Map.Entry<String, List<String>> entry : COMMAND_LOCALS.entrySet()) {
			Path file = REPO_ROOT.resolve(entry.getKey());
			assertTrue(Files.isRegularFile(file), "COMMAND LocalApi 文件不存在：" + file);
			String source = read(file);
			assertTrue(source.contains("InternalCallContext.call(operationId"),
					() -> entry.getKey() + " 没有使用带 operationId 的 InternalCallContext.call");
			for (String marker : entry.getValue()) {
				assertTrue(source.contains(marker), () -> entry.getKey() + " 缺少稳定 operationId 规则：" + marker);
			}
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}
