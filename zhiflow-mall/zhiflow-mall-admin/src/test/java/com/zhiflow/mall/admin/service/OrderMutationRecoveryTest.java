package com.zhiflow.mall.admin.service;

import com.zhiflow.api.mall.OrderInfoApi;
import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.common.dto.OrderMutationStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.*;

class OrderMutationRecoveryTest {

	@Test
	void unknownAppliedByStatusQueryBecomesSuccessWithoutReplay() {
		OrderInfoApi api = mock(OrderInfoApi.class);
		R<Void> unknown = unknownResult();
		when(api.mutationStatus("o-1", "order-cancel:o-1", "Y"))
				.thenReturn(R.ok(new OrderMutationStatus("o-1", "order-cancel:o-1", "CANCEL", "CANCELLED",
						"CANCEL", true, true)));

		R<Void> result = OrderMutationRecovery.recover(unknown, api, "o-1", "order-cancel:o-1", "Y");

		assertEquals(0, result.getCode());
		verify(api).mutationStatus("o-1", "order-cancel:o-1", "Y");
	}

	@Test
	void unknownWithoutConfirmedStatusRemainsUnknown() {
		OrderInfoApi api = mock(OrderInfoApi.class);
		R<Void> unknown = unknownResult();
		when(api.mutationStatus("o-1", "order-cancel:o-1", "Y"))
				.thenReturn(R.ok(new OrderMutationStatus("o-1", "order-cancel:o-1", "CANCEL", "PENDING_PAY",
						null, false, false)));

		R<Void> result = OrderMutationRecovery.recover(unknown, api, "o-1", "order-cancel:o-1", "Y");

		assertNotEquals(0, result.getCode());
		verify(api).mutationStatus("o-1", "order-cancel:o-1", "Y");
	}

	@Test
	void emptyCommandResponseUsesStatusQueryAndNeverLeaksNull() {
		OrderInfoApi api = mock(OrderInfoApi.class);
		when(api.mutationStatus("o-1", "order-cancel:o-1", "Y"))
				.thenReturn(R.ok(new OrderMutationStatus("o-1", "order-cancel:o-1", "CANCEL", "PENDING_PAY",
						null, false, false)));

		R<Void> result = OrderMutationRecovery.recover(null, api, "o-1", "order-cancel:o-1", "Y");

		assertNotEquals(0, result.getCode());
		verify(api).mutationStatus("o-1", "order-cancel:o-1", "Y");
	}

	@Test
	void knownResultDoesNotIssueStatusQuery() {
		OrderInfoApi api = mock(OrderInfoApi.class);
		R<Void> success = R.ok();

		assertEquals(0, OrderMutationRecovery.recover(success, api, "o-1", "order-cancel:o-1", "Y").getCode());

		verifyNoInteractions(api);
	}

	/** 单体里命令调用是进程内直调，「结果未知」只剩无响应（null）这一种形状。 */
	private static R<Void> unknownResult() {
		return null;
	}
}
