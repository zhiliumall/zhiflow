package com.zhiflow.mall.api.controller;

import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.common.portal.entity.ThirdSession;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import com.zhiflow.mall.api.service.OrderInfoService;
import com.zhiflow.mall.api.service.OrderItemService;
import com.zhiflow.mall.common.constant.MallReturnCode;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * C 组 M4：{@code GET /mallapi/orderitem/{id}}。
 *
 * <p>此前 {@code mustLogin = false} 且不校验归属：匿名按订单行 id 可读任意人买了什么、数量、实付。
 * 调用方只有 h5 / pc 的售后申请与售后详情页（登录态、本人订单）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderItemControllerOwnershipTest {

	@Mock
	private OrderItemService orderItemService;

	@Mock
	private OrderInfoService orderInfoService;

	@InjectMocks
	private OrderItemController controller;

	@BeforeEach
	void login() {
		ThirdSession s = new ThirdSession();
		s.setUserId("me");
		ThirdSessionHolder.setThirdSession(s);
	}

	@AfterEach
	void logout() {
		ThirdSessionHolder.clear();
	}

	private OrderItem item(String orderOwner) {
		OrderItem item = new OrderItem();
		item.setId("I1");
		item.setOrderId("O1");
		item.setSpuName("某商品");
		when(orderItemService.getById2("I1")).thenReturn(item);
		OrderInfo o = new OrderInfo();
		o.setId("O1");
		o.setUserId(orderOwner);
		when(orderInfoService.getById("O1")).thenReturn(o);
		return item;
	}

	@Test
	@DisplayName("必须登录：mustLogin=true（原来是 false = 匿名可达）")
	void requiresLogin() throws Exception {
		ApiLogin apiLogin = OrderItemController.class.getMethod("getById", String.class).getAnnotation(ApiLogin.class);
		assertNotNull(apiLogin);
		assertTrue(apiLogin.mustLogin());
	}

	@Test
	@DisplayName("别人订单的订单行 → ERR_70001，不回商品与金额")
	void othersItemRejected() {
		item("someone-else");
		R<?> r = controller.getById("I1");
		assertEquals(MallReturnCode.ERR_70001.getCode(), r.getCode());
		assertNull(r.getData());
	}

	@Test
	@DisplayName("本人订单的订单行 → 照常返回")
	void ownItemReturned() {
		OrderItem mine = item("me");
		R<?> r = controller.getById("I1");
		assertEquals(0, r.getCode());
		assertSame(mine, r.getData());
	}

	@Test
	@DisplayName("取不到（前端偶发传 undefined）→ 仍回 data:null，与原行为一致")
	void unknownItemStillOkNull() {
		when(orderItemService.getById2("undefined")).thenReturn(null);
		R<?> r = controller.getById("undefined");
		assertEquals(0, r.getCode());
		assertNull(r.getData());
	}
}
