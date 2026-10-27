package com.zhiflow.mall.admin.service.impl;

import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.mall.common.entity.OrderItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 后台发货的商品项清单——不传 = 全部 NORMAL 项；只能发本单的；没发过的已取消项跳过。 */
class OrderInfoServiceImplShipItemsTest {

	private static OrderItem item(String id, String itemStatus, boolean shipped) {
		OrderItem i = new OrderItem();
		i.setId(id);
		i.setSpuName("商品" + id);
		i.setItemStatus(itemStatus);
		if (shipped) {
			i.setDeliveryTime(LocalDateTime.now());
			i.setDeliveryType("1");
		}
		return i;
	}

	private static final List<OrderItem> ITEMS = List.of(item("a", "NORMAL", false), item("b", "NORMAL", false),
			item("c", "CANCELLED", false), item("d", "SHIPPED", true), item("e", "CANCELLED", true));

	@Test
	@DisplayName("不传：默认全部 NORMAL 项（已发、已取消的都不在内）")
	void defaultsToAllNormalItems() {
		assertEquals(List.of("a", "b"), OrderInfoServiceImpl.resolveShipItems(ITEMS, ""));
		assertEquals(List.of("a", "b"), OrderInfoServiceImpl.resolveShipItems(ITEMS, null));
	}

	@Test
	@DisplayName("不传且没有 NORMAL 项：拒")
	void nothingToShip() {
		BizException e = assertThrows(BizException.class,
				() -> OrderInfoServiceImpl.resolveShipItems(List.of(item("c", "CANCELLED", false)), " "));
		assertTrue(e.getMessage().contains("没有待发货"));
	}

	@Test
	@DisplayName("传了：按传的发；已发过的可以重复列出（修改物流单）；已取消但发过货的项，显式传也不跳过（跳过只认未发货的取消项）")
	void explicitList() {
		assertEquals(List.of("a", "d", "e"), OrderInfoServiceImpl.resolveShipItems(ITEMS, "a, d ,e"));
	}

	@Test
	@DisplayName("后台发货页把整单全部商品项都传上来：没发过的已取消项跳过（不记成发出），其余照发")
	void cancelledUnshippedSkipped() {
		assertEquals(List.of("a", "b", "d", "e"), OrderInfoServiceImpl.resolveShipItems(ITEMS, "a,b,c,d,e"));
	}

	@Test
	@DisplayName("跳过之后一项不剩：拒")
	void onlyCancelledListed() {
		BizException e = assertThrows(BizException.class, () -> OrderInfoServiceImpl.resolveShipItems(ITEMS, "c"));
		assertTrue(e.getMessage().contains("没有待发货"));
	}

	@Test
	@DisplayName("别的订单的商品项：拒（原来只按 id 更新，不看属于哪张单）")
	void foreignItemRejected() {
		BizException e = assertThrows(BizException.class, () -> OrderInfoServiceImpl.resolveShipItems(ITEMS, "a,zzz"));
		assertTrue(e.getMessage().contains("不属于"));
	}
}
