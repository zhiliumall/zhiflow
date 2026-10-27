package com.zhiflow.mall.api.trade;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.zhiflow.mall.api.mapper.PayOrderStateLogMapper;
import com.zhiflow.mall.api.mapper.PayOrderStateMachineMapper;
import com.zhiflow.mall.common.entity.PayOrder;
import com.zhiflow.mall.common.entity.PayOrderStateLog;
import com.zhiflow.mall.common.trade.statemachine.order.OrderTransitionedEvent;
import com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderPatch;
import com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderState;
import com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderStatePort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 支付单状态机端口的实现（v1.2 三章 / 7.1）：锁内重读、带 PG 触发器通行证的条件更新、退款占额、流水、进程内跳转事件。
 */
@RequiredArgsConstructor
public class PayOrderStatePortImpl implements PayOrderStatePort {

	private final PayOrderStateMachineMapper mapper;

	private final PayOrderStateLogMapper logMapper;

	private final ApplicationEventPublisher eventPublisher;

	private final IdentifierGenerator identifierGenerator;

	@Override
	public PayOrder loadForUpdate(String payNo) {
		return mapper.selectForUpdate(payNo);
	}

	@Override
	public int compareAndSet(String payNo, String expectedStatus, PayOrderState to, PayOrderPatch p) {
		mapper.setStateMachinePass("1");
		try {
			return mapper.compareAndSet(payNo, expectedStatus, to.code(), p.channelTxnId(), p.payTime(), p.latePaid(),
					p.duplicatePaid(), p.failReason(), p.channelClosed(), p.closedTime());
		}
		finally {
			// 通行证只覆盖上面这一条 UPDATE
			mapper.setStateMachinePass("0");
		}
	}



	@Override
	public void appendLog(PayOrderStateLog row) {
		logMapper.insert(row);
	}

	@Override
	public void publish(OrderTransitionedEvent event) {
		eventPublisher.publishEvent(event);
	}

	@Override
	public String nextId() {
		return String.valueOf(identifierGenerator.nextId(new PayOrderStateLog()));
	}
}
