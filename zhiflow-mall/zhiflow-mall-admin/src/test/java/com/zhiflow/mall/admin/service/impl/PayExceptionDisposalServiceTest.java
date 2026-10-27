package com.zhiflow.mall.admin.service.impl;

import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.mall.admin.mapper.PayExceptionMapper;
import com.zhiflow.mall.common.entity.PayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 差错池最小版：唯一动作「标记已处理」——OPEN → RESOLVED，必填备注，只能标一次。 */
class PayExceptionDisposalServiceTest {

	private final PayExceptionMapper exMapper = mock(PayExceptionMapper.class);

	private final PayExceptionDisposalService service = new PayExceptionDisposalService(exMapper);

	private PayException open(String id) {
		PayException ex = new PayException();
		ex.setId(id);
		ex.setType("LATE_PAID");
		ex.setStatus("OPEN");
		ex.setOrderNo("NO-1");
		when(exMapper.selectById(id)).thenReturn(ex);
		return ex;
	}

	@Test
	@DisplayName("标记已处理：OPEN → RESOLVED，resolution=MANUAL_HANDLED，备注落 resolve_reason")
	void resolveMarksOpenException() {
		open("E1");
		when(exMapper.finish(eq("E1"), eq("RESOLVED"), eq("MANUAL_HANDLED"), anyString(), eq("已在微信商户后台原路退款 R123")))
				.thenReturn(1);

		service.resolve("E1", "  已在微信商户后台原路退款 R123 ");

		verify(exMapper).finish(eq("E1"), eq("RESOLVED"), eq("MANUAL_HANDLED"), anyString(), eq("已在微信商户后台原路退款 R123"));
	}

	@Test
	@DisplayName("备注必填（空白也不行），不写库")
	void noteIsRequired() {
		open("E1");
		assertThrows(BizException.class, () -> service.resolve("E1", " "));
		assertThrows(BizException.class, () -> service.resolve("E1", null));
		verify(exMapper, never()).finish(anyString(), anyString(), anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("已处理过的不能再标；并发下条件更新落空也报「已被处理」")
	void onlyOpenCanBeResolved() {
		PayException ex = open("E2");
		ex.setStatus("RESOLVED");
		assertThrows(BizException.class, () -> service.resolve("E2", "x"));
		verify(exMapper, never()).finish(anyString(), anyString(), anyString(), anyString(), anyString());

		open("E3");
		when(exMapper.finish(eq("E3"), anyString(), anyString(), anyString(), anyString())).thenReturn(0);
		assertThrows(BizException.class, () -> service.resolve("E3", "x"));
	}

	@Test
	void unknownIdIsRejected() {
		assertThrows(BizException.class, () -> service.resolve("nope", "x"));
	}
}
