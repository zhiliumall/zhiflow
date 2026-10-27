package com.zhiflow.mall.common.trade.event;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zhiflow.mall.common.trade.statemachine.order.OrderTransitionedEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收「schema 改动删字段时 CI 失败」：标准交易事件只加字段不删字段。
 * 快照 {@code events/trade-event-v1.fields} 里的每个字段（名字 + 类型）都必须还在，而且真的出现在发出去的 JSON 里
 * （outbox 用 fastjson2 序列化）。
 */
class TradeEventSchemaTest {

	static Map<String, String> snapshot() {
		try (InputStream in = TradeEventSchemaTest.class.getResourceAsStream("/events/trade-event-v1.fields")) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
					.map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#"))
					.map(l -> l.split(":"))
					.collect(Collectors.toMap(p -> p[0], p -> p[1], (a, b) -> a, LinkedHashMap::new));
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void everyPublishedFieldIsStillThereWithTheSameType() {
		Map<String, String> current = Arrays.stream(OrderTransitionedEvent.class.getRecordComponents())
				.collect(Collectors.toMap(RecordComponent::getName, c -> c.getType().getSimpleName()));
		List<String> broken = snapshot().entrySet().stream()
				.filter(e -> !e.getValue().equals(current.get(e.getKey())))
				.map(e -> e.getKey() + ":" + e.getValue() + " → " + current.getOrDefault(e.getKey(), "已删除"))
				.toList();
		assertTrue(broken.isEmpty(), "标准交易事件删了 / 改了字段（只能加不能删）：" + broken
				+ "。确需变更：先按 public.event_subscriber 通知订阅方、升 version，再改快照");
		List<String> unlisted = current.keySet().stream().filter(k -> !snapshot().containsKey(k)).toList();
		assertTrue(unlisted.isEmpty(), "新加的字段要追加进快照（events/trade-event-v1.fields），下次才受保护：" + unlisted);
	}

	@Test
	void theWireJsonCarriesEverySnapshotField() {
		OrderTransitionedEvent e = new OrderTransitionedEvent("L1", OrderTransitionedEvent.TYPE, 1, "1",
				OffsetDateTime.parse("2026-09-26T21:30:00+08:00"), "o1", "NO1", "P1", "PENDING_PAY", "PENDING_SHIP",
				"PAY_SUCCESS", "O2", "CHANNEL", "u1", 28700L, "CNY", "trace-1");
		JSONObject wire = JSON.parseObject(JSON.toJSONString(e));
		for (String field : snapshot().keySet()) {
			assertTrue(wire.containsKey(field), "发出去的 JSON 里没有 " + field + "：" + wire);
		}
		assertEquals(1, wire.getIntValue("version"));
	}
}
