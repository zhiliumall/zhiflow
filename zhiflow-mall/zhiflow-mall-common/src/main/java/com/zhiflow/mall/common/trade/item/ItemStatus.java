package com.zhiflow.mall.common.trade.item;

import com.zhiflow.mall.common.entity.OrderItem;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * 商品项状态（order_item.item_status）。订单「发没发完」只由它的汇总推导。
 *
 * <p>跳转（都在触发它的那次订单跳转的同一事务里做条件更新，没有单独入口）：
 * <ul>
 *   <li>NORMAL → SHIPPED：后台发货（请求带商品项列表，默认全部 NORMAL 项）；</li>
 *   <li>任意 → CANCELLED：订单 O5（未付取消）。</li>
 * </ul>
 *
 * <p>item_status 为空时按发货痕迹推导，口径与 {@link ItemStatusSql#DERIVED} 一致。
 */
public enum ItemStatus {

	NORMAL, SHIPPED, CANCELLED;

	public static ItemStatus of(OrderItem i) {
		if (i.getItemStatus() != null) {
			for (ItemStatus s : values()) {
				if (s.name().equals(i.getItemStatus())) {
					return s;
				}
			}
		}
		return shipped(i) ? SHIPPED : NORMAL;
	}

	/** 有发货痕迹：新列 shipped_time，或老列 delivery_time / delivery_type。 */
	public static boolean shipped(OrderItem i) {
		return i.getShippedTime() != null || i.getDeliveryTime() != null || i.getDeliveryType() != null;
	}

	/** 一张订单的商品项汇总。 */
	public record Summary(Map<ItemStatus, Integer> counts, int total) {

		public int count(ItemStatus s) {
			return counts.getOrDefault(s, 0);
		}

		/** O10：没有 NORMAL 项且至少一项 SHIPPED（已取消的项不挡发货完成）。 */
		public boolean allShippable() {
			return count(NORMAL) == 0 && count(SHIPPED) > 0;
		}

		/** 前端「已发 x / n」的 x：当前 SHIPPED 的项。 */
		public int shipped() {
			return count(SHIPPED);
		}
	}

	public static Summary summarize(Collection<OrderItem> items) {
		Map<ItemStatus, Integer> m = new EnumMap<>(ItemStatus.class);
		for (OrderItem i : items) {
			m.merge(of(i), 1, Integer::sum);
		}
		return new Summary(m, items.size());
	}
}
