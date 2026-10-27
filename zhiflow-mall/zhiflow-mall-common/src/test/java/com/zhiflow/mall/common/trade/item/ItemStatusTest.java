package com.zhiflow.mall.common.trade.item;

import com.zhiflow.mall.common.entity.OrderItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 商品项状态：新列优先、为空按发货痕迹推导；订单主状态的汇总条件（O10）。 */
class ItemStatusTest {

	private static OrderItem item(String itemStatus, boolean shipped) {
		OrderItem i = new OrderItem();
		i.setItemStatus(itemStatus);
		if (shipped) {
			i.setDeliveryTime(LocalDateTime.now());
		}
		return i;
	}

	@Test
	@DisplayName("新列有值就用新列")
	void newColumnWins() {
		assertEquals(ItemStatus.SHIPPED, ItemStatus.of(item("SHIPPED", false)));
		assertEquals(ItemStatus.CANCELLED, ItemStatus.of(item("CANCELLED", true)));
	}

	@Test
	@DisplayName("为空按发货痕迹推导：有发货痕迹 > 正常")
	void legacyDerivation() {
		assertEquals(ItemStatus.NORMAL, ItemStatus.of(item(null, false)));
		assertEquals(ItemStatus.SHIPPED, ItemStatus.of(item(null, true)), "老列 delivery_time 也算发过");
		OrderItem typeOnly = item(null, false);
		typeOnly.setDeliveryType("1");
		assertEquals(ItemStatus.SHIPPED, ItemStatus.of(typeOnly), "只有发货类型也算发过");
		OrderItem newShipped = item(null, false);
		newShipped.setShippedTime(OffsetDateTime.now());
		assertEquals(ItemStatus.SHIPPED, ItemStatus.of(newShipped));
	}

	@Test
	@DisplayName("O10：没有 NORMAL 且至少一项 SHIPPED；已取消不挡")
	void allShippable() {
		assertTrue(ItemStatus.summarize(List.of(item("CANCELLED", false), item("SHIPPED", true), item("SHIPPED", true)))
				.allShippable(), "取消一件再发两件");
		assertFalse(ItemStatus.summarize(List.of(item("CANCELLED", false), item("NORMAL", false), item("NORMAL", false)))
				.allShippable(), "取消一件不发货");
		assertFalse(ItemStatus.summarize(List.of(item("CANCELLED", false))).allShippable(),
				"一件都没发（全取消）不算发货完成");
	}

	@Test
	@DisplayName("「已发 x / n」")
	void shippedCount() {
		ItemStatus.Summary s = ItemStatus
				.summarize(List.of(item("SHIPPED", true), item("SHIPPED", true), item("NORMAL", false)));
		assertEquals(2, s.shipped());
		assertEquals(3, s.total());
	}
}
