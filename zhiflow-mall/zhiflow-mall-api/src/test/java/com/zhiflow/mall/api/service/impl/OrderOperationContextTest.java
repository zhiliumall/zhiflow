package com.zhiflow.mall.api.service.impl;

import com.zhiflow.common.security.internal.InternalCallContext;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.api.controller.OrderInfoController;
import com.zhiflow.mall.api.local.OrderInfoLocalApi;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.dto.OrderMutationStatus;
import com.zhiflow.mall.common.enums.OptSourceEnum;
import com.zhiflow.mall.common.trade.statemachine.Transition;
import com.zhiflow.mall.common.trade.statemachine.order.OrderEvent;
import com.zhiflow.mall.common.trade.statemachine.order.OrderState;
import com.zhiflow.mall.common.trade.statemachine.order.OrderStateMachine;
import com.zhiflow.mall.common.trade.statemachine.order.OrderTransitionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies transport-neutral operation correlation reaches the actual order transition boundary. */
@ExtendWith(MockitoExtension.class)
class OrderOperationContextTest {

	@Mock
	private OrderStateMachine orderStateMachine;

	@Mock
	private OrderInfoController orderInfoController;

	@InjectMocks
	private OrderInfoServiceImpl service;

	@Test
	void cancelPassesOperationIdAndKeepsTransitionResult() {
		when(this.orderStateMachine.fire(eq("o-1"), eq(OrderEvent.CANCEL), any()))
				.thenReturn(Transition.ok(OrderState.PENDING_PAY, OrderState.CANCELLED, "O5", "log-1"));
		OrderInfo order = order("o-1", null, "1");

		assertTrue(InternalCallContext.call("order-cancel:o-1", () -> this.service.fireCancel(order,
				OptSourceEnum.SYSTEM)));
		assertEquals("5", order.getStatus());
		assertEquals("order-cancel:o-1", transitionContext(OrderEvent.CANCEL).requestId());
		assertNull(InternalCallContext.currentOperationId());
	}

	@Test
	void receivePassesOperationIdAndStopsOnNoop() {
		when(this.orderStateMachine.fire(eq("o-1"), eq(OrderEvent.RECEIVE), any()))
				.thenReturn(Transition.noop(OrderState.COMPLETED));

		InternalCallContext.run("order-receive:o-1", () -> this.service.orderReceive(
				order("o-1", "2", "1"), OptSourceEnum.SELLER));

		assertEquals("order-receive:o-1", transitionContext(OrderEvent.RECEIVE).requestId());
		assertNull(InternalCallContext.currentOperationId());
	}

	@Test
	void legacyCallDoesNotInventOperationIdOrRepeatCancelSideEffects() {
		when(this.orderStateMachine.fire(eq("o-1"), eq(OrderEvent.CANCEL), any()))
				.thenReturn(Transition.noop(OrderState.CANCELLED));

		assertFalse(this.service.fireCancel(order("o-1", "5", "1"), OptSourceEnum.SYSTEM));
		assertNull(transitionContext(OrderEvent.CANCEL).requestId());
	}

	@Test
	void localOrderAdapterUsesTheSameStableOperationIdsAsGrpc() {
		when(this.orderInfoController.orderCancelInside("o-1")).thenAnswer(call -> {
			assertEquals("order-cancel:o-1", InternalCallContext.currentOperationId());
			return R.<Void>ok();
		});
		when(this.orderInfoController.mutationStatus("o-3", "order-cancel:o-3")).thenAnswer(call -> {
			assertNull(InternalCallContext.currentOperationId(), "状态查询不能继承写命令 operationId");
			return R.ok(new OrderMutationStatus("o-3", "order-cancel:o-3", "CANCEL", "CANCELLED",
					"CANCEL", true, true));
		});

		OrderInfoLocalApi local = new OrderInfoLocalApi(this.orderInfoController);
		local.orderCancelInside("o-1", SecurityConstants.FROM_IN);
		assertNull(InternalCallContext.currentOperationId());
		assertTrue(local.mutationStatus("o-3", "order-cancel:o-3", SecurityConstants.FROM_IN).getData().isApplied());
		assertNull(InternalCallContext.currentOperationId());
	}

	private OrderTransitionContext transitionContext(OrderEvent event) {
		ArgumentCaptor<OrderTransitionContext> context = ArgumentCaptor.forClass(OrderTransitionContext.class);
		verify(this.orderStateMachine).fire(eq("o-1"), eq(event), context.capture());
		return context.getValue();
	}

	private static OrderInfo order(String id, String status, String deliveryWay) {
		OrderInfo order = new OrderInfo();
		order.setId(id);
		order.setOrderNo("NO-1");
		order.setStatus(status);
		order.setDeliveryWay(deliveryWay);
		return order;
	}

}
