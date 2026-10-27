package com.zhiflow.mall.api.controller;

import com.zhiflow.common.portal.entity.ThirdSession;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import com.zhiflow.mall.api.service.OrderInfoService;
import com.zhiflow.mall.api.trade.PayStatusService;
import com.zhiflow.mall.common.entity.OrderInfo;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** v1.2 阶段 2 验收：「pay-status 用别人的订单 id 返回 404」；自己的单返回当前状态与操作。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderInfoControllerPayStatusTest {

	@Mock
	private OrderInfoService orderInfoService;

	@Mock
	private PayStatusService payStatusService;

	@InjectMocks
	private OrderInfoController controller;

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

	private static OrderInfo order(String userId) {
		OrderInfo o = new OrderInfo();
		o.setId("O1");
		o.setUserId(userId);
		o.setIsPay("0");
		return o;
	}

	@Test
	@DisplayName("别人的订单 / 不存在的订单 → HTTP 404（不回 403，不泄露存在性），也不向渠道探查")
	void othersOrderIs404() {
		when(orderInfoService.getById("O1")).thenReturn(order("someone-else"));
		assertEquals(404, controller.payStatus("O1", true, null).getStatusCode().value());
		when(orderInfoService.getById("O2")).thenReturn(null);
		assertEquals(404, controller.payStatus("O2", true, null).getStatusCode().value());
		verify(payStatusService, never()).query(any(), anyBoolean(), any(), any());
		verify(orderInfoService, never()).getById2(any());
	}

	@Test
	@DisplayName("自己的订单 → 200，查询交给 PayStatusService（操作由它按状态机算）")
	void ownOrder() {
		OrderInfo mine = order("me");
		when(orderInfoService.getById("O1")).thenReturn(mine);
		when(orderInfoService.getById2("O1")).thenReturn(mine);
		var resp = controller.payStatus("O1", true, 0L);
		assertEquals(200, resp.getStatusCode().value());
		verify(payStatusService).query(any(), org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.eq(0L), any());
	}

	@Test
	void detailRejectsForeignOrderBeforeLazyCancellation() {
		when(orderInfoService.getById("O1")).thenReturn(order("someone-else"));
		assertEquals("无权访问此订单", controller.getById("O1").getMsg());
		assertNull(controller.getById("O2").getData());
		verify(orderInfoService, never()).getById2(any());
	}

	@Test
	void ownDetailKeepsLazyCancellationAndActions() {
		OrderInfo mine = order("me");
		when(orderInfoService.getById("O1")).thenReturn(mine);
		when(orderInfoService.getById2("O1")).thenReturn(mine);
		assertEquals(mine, controller.getById("O1").getData());
		verify(orderInfoService).getById2("O1");
		assertNotNull(mine.getActions());
	}
}
