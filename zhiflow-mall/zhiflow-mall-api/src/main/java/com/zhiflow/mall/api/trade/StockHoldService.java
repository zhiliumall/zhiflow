package com.zhiflow.mall.api.trade;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.zhiflow.mall.api.mapper.StockHoldMapper;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderItem;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 库存预占：下单在扣库存的同一事务写 HOLD；付款置 CONFIRMED；取消 / 关闭放出 HOLD 并回补。
 *
 * <p><b>回补只此一处</b>：取消路径（用户 / 超时 / 后台）都经 {@link #restoreOnCancel}——有 HOLD 行的订单只回补「这次真的从 HOLD
 * 放出来的」行（条件更新，取消与兜底任务并发也只回补一次）；没有 HOLD 行的订单（开关打开前下的、或开关关着时下的）
 * 仍走调用方原来的逐项回补，行为不变。
 *
 * <p>开关 {@code stock.hold.enabled}（配置项，默认关）只控制「下单时写不写 HOLD」；已有的 HOLD 行无论开关都按 HOLD 规则处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockHoldService {

	public static final String ENABLED_KEY = "stock.hold.enabled";

	/** HOLD 比订单超时多留的分钟数（订单超时 + 5 分钟）。 */
	public static final long GRACE_MINUTES = 5;

	public static final String REASON_CANCEL = "ORDER_CANCEL";

	public static final String REASON_EXPIRED = "EXPIRED_HOLD";

	/** 兜底任务触发的取消把放出原因记为 EXPIRED_HOLD（取消逻辑本身不变）。 */
	private static final ThreadLocal<String> REASON = new ThreadLocal<>();

	private final StockHoldMapper mapper;

	private final IdentifierGenerator idGenerator;

	private final Environment env;

	public boolean enabled() {
		return Boolean.TRUE.equals(env.getProperty(ENABLED_KEY, Boolean.class, Boolean.FALSE));
	}

	/** 下单：扣库存之后、同一事务内，逐项写 HOLD（开关关着不写）。 */
	public void hold(OrderInfo order, List<OrderItem> items, long orderTimeoutMinutes) {
		if (!enabled() || items == null) {
			return;
		}
		for (OrderItem i : items) {
			if (i.getSkuId() == null || i.getQuantity() == null || i.getQuantity() <= 0) {
				continue;
			}
			mapper.insertHold(String.valueOf(idGenerator.nextId(i)), order.getId(), i.getId(), i.getSkuId(), i.getQuantity(),
					orderTimeoutMinutes + GRACE_MINUTES, order.getTenantId());
		}
	}

	/**
	 * 取消 / 关闭时的库存回补（调用方在自己的事务里、状态机跳转成功之后调用）。
	 *
	 * @param legacyRestock 这单没有 HOLD 行时，调用方原来的逐项回补（skuId, qty）
	 * @return true = 这单走了 HOLD（已按放出的行回补）；false = 没有 HOLD 行，调用方应按原逻辑逐项回补
	 */
	public boolean restoreOnCancel(String orderId, BiConsumer<String, Integer> legacyRestock) {
		String reason = REASON.get() != null ? REASON.get() : REASON_CANCEL;
		List<Map<String, Object>> released = mapper.releaseHolds(orderId, reason);
		if (released.isEmpty() && mapper.countHolds(orderId) == 0) {
			return false;
		}
		// 按 skuId 升序回补（UPDATE … RETURNING 的返回顺序不保证）：与下单扣减同一加锁顺序，见 OrderStockDeduction
		released.stream()
				.sorted(Comparator.comparing(r -> String.valueOf(r.get("skuId"))))
				.forEach(r -> legacyRestock.accept(String.valueOf(r.get("skuId")), ((Number) r.get("qty")).intValue()));
		Metrics.counter("stock_hold_released_total", "reason", reason).increment(released.size());
		if (released.isEmpty()) {
			log.info("订单 {} 的库存预占已被处理过（放出或确认），本次不再回补", orderId);
		}
		return true;
	}

	/** 付款成功（O2 同一事务）：HOLD → CONFIRMED。 */
	public void confirm(String orderId) {
		int n = mapper.confirmHolds(orderId);
		if (n > 0) {
			Metrics.counter("stock_hold_confirmed_total").increment(n);
		}
	}

	/** 在「过期兜底」语境下执行（放出原因记 EXPIRED_HOLD）。 */
	public <T> T asExpired(Supplier<T> body) {
		REASON.set(REASON_EXPIRED);
		try {
			return body.get();
		}
		finally {
			REASON.remove();
		}
	}
}
