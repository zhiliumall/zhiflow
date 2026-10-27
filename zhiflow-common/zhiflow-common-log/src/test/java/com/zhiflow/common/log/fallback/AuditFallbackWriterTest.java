package com.zhiflow.common.log.fallback;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import com.zhiflow.common.log.config.BaseLogProperties;
import com.zhiflow.upms.common.dto.SysLogPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditFallbackWriterTest {

	private static BaseLogProperties properties(boolean fallbackToFile) {
		BaseLogProperties properties = new BaseLogProperties();
		properties.setFallbackToFile(fallbackToFile);
		return properties;
	}

	private static SysLogPayload sampleLog() {
		SysLogPayload sysLog = new SysLogPayload();
		sysLog.setTitle("删除商品");
		sysLog.setRequestUri("/goods/spu/123");
		sysLog.setMethod("DELETE");
		return sysLog;
	}

	@Test
	@DisplayName("上报失败会计数，且不向上抛异常")
	void reportFailureIsCountedAndNeverThrows() {
		AuditFallbackWriter writer = new AuditFallbackWriter(properties(true));

		assertDoesNotThrow(() -> writer.write(sampleLog(), "上报超时"));
		assertDoesNotThrow(() -> writer.write(sampleLog(), "连接被拒"));

		assertEquals(2, writer.getDroppedCount());
	}

	@Test
	@DisplayName("队列溢出会计数（内容取不到，但丢弃事件本身必须可见）")
	void queueOverflowIsCounted() {
		AuditFallbackWriter writer = new AuditFallbackWriter(properties(true));

		writer.writeQueueOverflow();
		writer.writeQueueOverflow();

		assertEquals(2, writer.getDroppedCount());
	}

	@Test
	@DisplayName("fallbackToFile=false 时仍然计数（只是不落兜底文件）")
	void countingWorksEvenWhenFileFallbackDisabled() {
		AuditFallbackWriter writer = new AuditFallbackWriter(properties(false));

		writer.write(sampleLog(), "上报超时");

		assertEquals(1, writer.getDroppedCount());
	}

	@Test
	@DisplayName("SysLogPayload 含不可序列化内容时不抛异常（兜底不能成为新的故障点）")
	void serializationFailureIsSwallowed() {
		AuditFallbackWriter writer = new AuditFallbackWriter(properties(true));
		SysLogPayload sysLog = sampleLog();
		// params 放超长内容，确保序列化路径被走到
		sysLog.setParams("x".repeat(100_000));

		assertDoesNotThrow(() -> writer.write(sysLog, "上报超时"));
		assertEquals(1, writer.getDroppedCount());
	}

	@Test
	@DisplayName("logback-audit-fallback.xml 能被 logback 真实解析且产出 auditFallback appender")
	void fallbackAppenderConfigIsLoadable() throws Exception {
		// 直接喂给 Joran 解析：光校验 XML 格式不够——appender class 写错、rollingPolicy
		// 配错都只有真实解析才会暴露，而这类错误会让兜底文件静默不生成。
		String config = """
				<configuration>
					<property name="log.path" value="target/test-logs"/>
					<include resource="logback-audit-fallback.xml"/>
				</configuration>
				""";

		LoggerContext context = new LoggerContext();
		context.setName("audit-fallback-test");
		JoranConfigurator configurator = new JoranConfigurator();
		configurator.setContext(context);
		configurator.doConfigure(new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8)));

		var errors = context.getStatusManager().getCopyOfStatusList().stream()
				.filter(status -> status.getLevel() >= ch.qos.logback.core.status.Status.WARN)
				.toList();
		assertTrue(errors.isEmpty(), "logback 配置有告警/错误: " + errors);

		ch.qos.logback.classic.Logger fallbackLogger =
				context.getLogger(AuditFallbackWriter.FALLBACK_LOGGER_NAME);
		assertNotNull(fallbackLogger.getAppender("auditFallback"),
				"AUDIT_FALLBACK logger 上没挂到 auditFallback appender");
		assertTrue(!fallbackLogger.isAdditive(), "additivity 应为 false，避免兜底内容重复进主日志");
	}

	@Test
	@DisplayName("独立 logger 名称保持稳定（logback 配置与代码靠它对齐）")
	void loggerNameIsStable() {
		assertEquals("AUDIT_FALLBACK", AuditFallbackWriter.FALLBACK_LOGGER_NAME);
		assertNotNull(LoggerFactory.getLogger(AuditFallbackWriter.FALLBACK_LOGGER_NAME));
	}
}
