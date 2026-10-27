package com.zhiflow.common.log.fallback;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.zhiflow.common.log.config.BaseLogProperties;
import com.zhiflow.upms.common.dto.SysLogPayload;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计日志落库失败时的本地兜底。
 *
 * <p><b>存在理由</b>：改造前审计日志有两个静默丢弃点，丢了之后无处追查——
 * <ol>
 *   <li>{@code SysLogListener} 上报超时/异常只 {@code log.warn} 一句，注释直言「审计日志可丢」；</li>
 *   <li>{@code sysLogExecutor} 用 {@code DiscardOldestPolicy}，队列满即弃最旧任务，
 *       <b>丢弃时连一行日志都没有</b>。</li>
 * </ol>
 * 第 2 点在 C 端全量补齐 {@code @SysLogPayload} 后尤其危险：upms 稍慢就静默丢日志。
 *
 * <p>兜底只落盘、不做同步重试——保留既有的「绝不回灌请求线程」决策：审计可靠性不能
 * 用用户请求的延迟来换。
 *
 * <p>写到独立 logger {@code AUDIT_FALLBACK}，便于在各服务 logback 配置里挂单独的
 * 文件 appender 并接告警。未配置 appender 时会落到主日志，仍然可查（比丢掉好）。
 */
@RequiredArgsConstructor
public class AuditFallbackWriter {

	/** 独立 logger name：供 logback 配置单独的 appender + 告警规则 */
	public static final String FALLBACK_LOGGER_NAME = "AUDIT_FALLBACK";

	private static final Logger FALLBACK_LOG = LoggerFactory.getLogger(FALLBACK_LOGGER_NAME);

	private static final Logger log = LoggerFactory.getLogger(AuditFallbackWriter.class);

	private static final ObjectMapper MAPPER = new JsonMapper();

	private final BaseLogProperties properties;

	/** 丢弃计数，供看板的「失败审计覆盖率」指标取数 */
	private final AtomicLong droppedCount = new AtomicLong();

	/**
	 * @param reason 丢弃原因，用于区分「上报失败」与「队列溢出」两类
	 */
	public void write(SysLogPayload sysLog, String reason) {
		long dropped = droppedCount.incrementAndGet();

		// 原先这里是 log.warn，导致审计丢失在告警里看不见。审计丢失是可靠性事件，用 error。
		log.error("审计日志落库失败，已转本地兜底。reason={} uri={} title={} 累计丢弃={}",
				reason, sysLog.getRequestUri(), sysLog.getTitle(), dropped);

		if (!properties.isFallbackToFile()) {
			return;
		}
		try {
			FALLBACK_LOG.error("{}", MAPPER.writeValueAsString(new FallbackRecord(reason, sysLog)));
		}
		catch (Exception e) {
			// 兜底自身失败只能退到纯文本，不能再抛
			FALLBACK_LOG.error("序列化审计兜底记录失败 reason={} uri={} err={}",
					reason, sysLog.getRequestUri(), e.getMessage());
		}
	}

	/**
	 * 线程池队列溢出导致的丢弃。
	 *
	 * <p>这里拿不到被弃日志的内容：{@code @Async} 已把任务包装成 {@code FutureTask}，
	 * 拒绝策略只能看到 {@code Runnable}，无法还原原始 {@code SysLogPayload}。所以只记「丢了几条」。
	 * 即便如此也远好于改造前——原先 {@code DiscardOldestPolicy} 丢弃时连一行日志都没有，
	 * 审计静默缺失完全不可发现。
	 */
	public void writeQueueOverflow() {
		long dropped = droppedCount.incrementAndGet();
		log.error("审计日志线程池队列已满，丢弃 1 条（内容无法还原，@Async 已包装任务）。累计丢弃={}", dropped);
	}

	public long getDroppedCount() {
		return droppedCount.get();
	}

	/** 兜底文件的一行记录 */
	private record FallbackRecord(String reason, SysLogPayload sysLog) {
	}
}
