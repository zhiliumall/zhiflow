package com.zhiflow.mall.common.trade.statemachine.order;

import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderStateLog;
import com.zhiflow.mall.common.trade.statemachine.LockRunner;
import com.zhiflow.mall.common.trade.statemachine.TxRunner;

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
 * 内存版端口：条件更新用对象锁保证原子（等价于数据库的行级 CAS），事务用「失败即回滚快照」模拟。
 * 只用于状态机的穷举 / 并发测试，真实 SQL 在各服务的端口实现与本地 PG 集成测试里验。
 */
final class InMemoryOrderPort implements OrderStatePort {

	final Map<String, OrderInfo> rows = new ConcurrentHashMap<>();

	final List<OrderStateLog> logs = Collections.synchronizedList(new ArrayList<>());

	final List<OrderTransitionedEvent> events = Collections.synchronizedList(new ArrayList<>());

	/** 被置 CANCELLED 的订单（按调用顺序）。 */
	final List<String> itemsCancelled = Collections.synchronizedList(new ArrayList<>());

	/** 自提核销后被置 SHIPPED 的订单。 */

	private final AtomicLong ids = new AtomicLong();

	/** 在 load 与 CAS 之间插入的钩子（模拟锁失效时的并发写） */
	volatile Runnable betweenLoadAndCas;

	void put(OrderInfo o) {
		rows.put(o.getId(), o);
	}

	@Override
	public OrderInfo loadForUpdate(String orderId) {
		OrderInfo o = rows.get(orderId);
		if (o == null) {
			return null;
		}
		OrderInfo copy = copy(o);
		Runnable hook = betweenLoadAndCas;
		if (hook != null) {
			hook.run();
		}
		return copy;
	}

	@Override
	public int compareAndSet(String orderId, String expectedStatus, String expectedIsPay, OrderState to,
			OrderPatch patch) {
		OrderInfo o = rows.get(orderId);
		synchronized (o) {
			if (!Objects.equals(o.getStatus(), expectedStatus) || !Objects.equals(o.getIsPay(), expectedIsPay)) {
				return 0;
			}
			o.setStatus(to.statusCode());
			o.setIsPay(to.isPayCode());
			if (patch.cancelTime() != null) {
				o.setCancelTime(patch.cancelTime());
			}
			if (patch.paymentType() != null) {
				o.setPaymentType(patch.paymentType());
			}
			if (patch.transactionId() != null) {
				o.setTransactionId(patch.transactionId());
			}
			return 1;
		}
	}

	@Override
	public void appendLog(OrderStateLog row) {
		logs.add(row);
	}

	@Override
	public void publish(OrderTransitionedEvent event) {
		events.add(event);
	}

	@Override
	public void markItemsCancelled(String orderId) {
		itemsCancelled.add(orderId);
	}


	@Override
	public String nextId() {
		return "L" + ids.incrementAndGet();
	}

	static OrderInfo copy(OrderInfo o) {
		OrderInfo c = new OrderInfo();
		c.setId(o.getId());
		c.setOrderNo(o.getOrderNo());
		c.setTenantId(o.getTenantId());
		c.setStatus(o.getStatus());
		c.setIsPay(o.getIsPay());
		c.setDeliveryWay(o.getDeliveryWay());
		c.setPaymentPrice(o.getPaymentPrice());
		return c;
	}

	/** 按 key 的可重入锁（等价于 Redisson 的 RLock，同线程可重入）。 */
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

	static LockRunner noLock() {
		return new LockRunner() {
			@Override
			public <T> T withLock(String key, java.time.Duration wait, java.util.function.Supplier<T> body) {
				return body.get();
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
