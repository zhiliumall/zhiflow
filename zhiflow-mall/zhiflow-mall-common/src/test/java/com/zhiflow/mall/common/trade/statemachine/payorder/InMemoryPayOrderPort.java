package com.zhiflow.mall.common.trade.statemachine.payorder;

import com.zhiflow.mall.common.entity.PayOrder;
import com.zhiflow.mall.common.entity.PayOrderStateLog;
import com.zhiflow.mall.common.trade.statemachine.LockRunner;
import com.zhiflow.mall.common.trade.statemachine.TxRunner;
import com.zhiflow.mall.common.trade.statemachine.order.OrderTransitionedEvent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存版支付单端口：条件更新 / 占额用对象锁保证原子（等价于行级条件 UPDATE），SQL 语义与 PayOrderStateMachineMapper 一一对应。
 */
final class InMemoryPayOrderPort implements PayOrderStatePort {

	final Map<String, PayOrder> rows = new ConcurrentHashMap<>();

	final List<PayOrderStateLog> logs = Collections.synchronizedList(new ArrayList<>());

	final List<OrderTransitionedEvent> events = Collections.synchronizedList(new ArrayList<>());

	private final AtomicLong ids = new AtomicLong();

	volatile Runnable betweenLoadAndCas;

	void put(PayOrder p) {
		rows.put(p.getPayNo(), p);
	}

	static PayOrder payOrder(String payNo, String status, long amountMinor) {
		PayOrder p = new PayOrder();
		p.setId("id-" + payNo);
		p.setPayNo(payNo);
		p.setBizId("order-1");
		p.setOrderNo("NO-1");
		p.setTenantId("1");
		p.setStatus(status);
		p.setAmount(BigDecimal.valueOf(amountMinor, 2));
		p.setAmountMinor(amountMinor);
		p.setCurrency("HKD");
		p.setChannelTxnId(null);
		return p;
	}

	@Override
	public PayOrder loadForUpdate(String payNo) {
		PayOrder p = rows.get(payNo);
		if (p == null) {
			return null;
		}
		PayOrder copy = copy(p);
		Runnable hook = betweenLoadAndCas;
		if (hook != null) {
			hook.run();
		}
		return copy;
	}

	@Override
	public int compareAndSet(String payNo, String expectedStatus, PayOrderState to, PayOrderPatch patch) {
		PayOrder p = rows.get(payNo);
		synchronized (p) {
			if (!Objects.equals(p.getStatus(), expectedStatus)) {
				return 0;
			}
			p.setStatus(to.code());
			if (patch.channelTxnId() != null) {
				p.setChannelTxnId(patch.channelTxnId());
			}
			if (patch.payTime() != null) {
				p.setPayTime(patch.payTime());
			}
			if (patch.latePaid() != null) {
				p.setLatePaid(patch.latePaid());
			}
			if (patch.duplicatePaid() != null) {
				p.setDuplicatePaid(patch.duplicatePaid());
			}
			if (patch.failReason() != null) {
				p.setFailReason(patch.failReason());
			}
			if (patch.channelClosed() != null) {
				p.setChannelClosed(patch.channelClosed());
			}
			if (patch.closedTime() != null) {
				p.setClosedTime(patch.closedTime());
			}
			return 1;
		}
	}

	@Override
	public void appendLog(PayOrderStateLog row) {
		logs.add(row);
	}

	@Override
	public void publish(OrderTransitionedEvent event) {
		events.add(event);
	}

	@Override
	public String nextId() {
		return "PL" + ids.incrementAndGet();
	}

	static PayOrder copy(PayOrder p) {
		PayOrder c = new PayOrder();
		c.setId(p.getId());
		c.setPayNo(p.getPayNo());
		c.setBizId(p.getBizId());
		c.setOrderNo(p.getOrderNo());
		c.setTenantId(p.getTenantId());
		c.setStatus(p.getStatus());
		c.setAmount(p.getAmount());
		c.setAmountMinor(p.getAmountMinor());
		c.setCurrency(p.getCurrency());
		c.setChannelTxnId(p.getChannelTxnId());
		return c;
	}

	static LockRunner locks() {
		Map<String, ReentrantLock> m = new ConcurrentHashMap<>();
		return new LockRunner() {
			@Override
			public <T> T withLock(String key, java.time.Duration wait, java.util.function.Supplier<T> body) {
				ReentrantLock l = m.computeIfAbsent(key, k -> new ReentrantLock());
				try {
					if (!l.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS)) {
						throw new IllegalStateException("lock timeout " + key);
					}
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(e);
				}
				try {
					return body.get();
				}
				finally {
					l.unlock();
				}
			}
		};
	}

	static TxRunner directTx() {
		return new TxRunner() {
			@Override
			public <T> T inTx(java.util.function.Supplier<T> body) {
				return body.get();
			}
		};
	}
}
