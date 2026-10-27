package com.zhiflow.mall.api.trade;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.zhiflow.mall.api.mapper.OrderItemMapper;
import com.zhiflow.mall.api.mapper.OrderStateLogMapper;
import com.zhiflow.mall.api.mapper.OrderStateMachineMapper;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderStateLog;
import com.zhiflow.mall.common.trade.statemachine.order.OrderPatch;
import com.zhiflow.mall.common.trade.statemachine.order.OrderState;
import com.zhiflow.mall.common.trade.statemachine.order.OrderStatePort;
import com.zhiflow.mall.common.trade.statemachine.order.OrderTransitionedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 订单状态机端口的实现：锁内重读、带 PG 触发器通行证的条件更新、流水、进程内跳转事件。
 * 与另一个服务里的同名类同形。
 */
@RequiredArgsConstructor
public class OrderStatePortImpl implements OrderStatePort {

	private final OrderStateMachineMapper mapper;

	private final OrderStateLogMapper logMapper;

	private final ApplicationEventPublisher eventPublisher;

	private final IdentifierGenerator identifierGenerator;

	private final OrderItemMapper orderItemMapper;

	@Override
	public OrderInfo loadForUpdate(String orderId) {
		return mapper.selectForUpdate(orderId);
	}

	@Override
	public int compareAndSet(String orderId, String expectedStatus, String expectedIsPay, OrderState to,
			OrderPatch p) {
		mapper.setStateMachinePass("1");
		try {
			return mapper.compareAndSet(orderId, expectedStatus, expectedIsPay, to.statusCode(), to.isPayCode(),
					p.paymentTime(), p.transactionId(), p.paymentType(), p.tradeType(), p.cancelTime(),
					p.receiverTime(), p.deliveryTime());
		}
		finally {
			// 通行证只覆盖上面这一条 UPDATE：同一事务里后续别的写（旁路）照样会被触发器记下来
			mapper.setStateMachinePass("0");
		}
	}

	@Override
	public void appendLog(OrderStateLog row) {
		logMapper.insert(row);
	}

	@Override
	public void publish(OrderTransitionedEvent event) {
		eventPublisher.publishEvent(event);
	}

	@Override
	public void markItemsCancelled(String orderId) {
		orderItemMapper.markCancelled(orderId);
	}


	@Override
	public String nextId() {
		return String.valueOf(identifierGenerator.nextId(new OrderStateLog()));
	}
}
