package com.zhiflow.mall.common.trade.payexception;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 不进表的差错类型「这一笔是不是窗口内第一次报」（差错池最小版）。
 *
 * <p>进表的两类靠 {@code dedupe_key} 唯一索引做到「同一笔钱只入一次」；不进表的类型没有表可去重，
 * 而巡检（每小时）、退款轮询（每分钟）每一轮都会重新发现同一批存量单——不去重的话同一批单每轮都计数、
 * 每轮都触发严重级告警 {@code PayExceptionRecorded}（2026-09-26 上线当晚：巡检首轮 30 笔存量卡单即触发，
 * 按小时会一直重复）。
 *
 * <p>返回 true = 窗口内第一次，应打 ERROR 并计数；false = 窗口内已报过。
 * 实现抛异常时入池器按「第一次」处理：宁可重复告警，不可静默。
 */
@FunctionalInterface
public interface PayExceptionLoggedDedupe {

	boolean firstSeen(String dedupeKey);

	/**
	 * 进程内版本：只在本实例内有效、重启后会再报一次。给单测和没有 Redis 的场景用；
	 * 实际部署都接 Redis 版（{@code SET NX EX}），跨实例、跨重启都只报一次。
	 */
	static PayExceptionLoggedDedupe inMemory(Duration window, LongSupplier clockMillis) {
		long windowMillis = window.toMillis();
		ConcurrentHashMap<String, Long> seenAt = new ConcurrentHashMap<>();
		return key -> {
			long now = clockMillis.getAsLong();
			if (seenAt.size() > 10_000) {
				seenAt.values().removeIf(t -> now - t >= windowMillis);
			}
			boolean[] first = { false };
			seenAt.compute(key, (k, last) -> {
				if (last == null || now - last >= windowMillis) {
					first[0] = true;
					return now;
				}
				return last;
			});
			return first[0];
		};
	}

}
