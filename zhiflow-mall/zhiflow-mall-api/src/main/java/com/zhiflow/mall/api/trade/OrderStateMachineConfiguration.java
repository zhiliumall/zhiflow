package com.zhiflow.mall.api.trade;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.zhiflow.mall.common.trade.statemachine.LockRunner;
import com.zhiflow.mall.common.trade.statemachine.TxRunner;
import com.zhiflow.mall.common.trade.statemachine.order.OrderStateMachine;
import com.zhiflow.mall.api.mapper.OrderStateLogMapper;
import com.zhiflow.mall.api.mapper.OrderStateMachineMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 状态机装配：编程式锁 + 事务模板 + 端口。
 *
 * <p>锁键 {@code lock:order:{id}} 与 {@code @DistributedLock(name = "order", keys = "#id")} 完全一致
 * （{@code DistributedLockAspect}：{@code "lock:" + name + ":" + key}），Redisson RLock 同线程可重入——
 * 已持注解锁的 notifyOrder 等内部再 fire 不会自锁；等锁 10 秒，看门狗续期。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class OrderStateMachineConfiguration {

	@Bean
	public LockRunner orderLockRunner(RedissonClient redissonClient) {
		return new LockRunner() {
			@Override
			public <T> T withLock(String key, java.time.Duration wait, java.util.function.Supplier<T> body) {
				RLock lock = redissonClient.getLock("lock:" + key);
				boolean acquired;
				try {
					acquired = lock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("等待锁被中断：" + key, e);
				}
				if (!acquired) {
					throw new IllegalStateException("订单正在处理中，请稍后重试（" + key + "）");
				}
				try {
					return body.get();
				}
				finally {
					if (lock.isHeldByCurrentThread()) {
						lock.unlock();
					}
				}
			}
		};
	}

	@Bean
	public TxRunner orderTxRunner(PlatformTransactionManager transactionManager) {
		TransactionTemplate tt = new TransactionTemplate(transactionManager);
		return new TxRunner() {
			@Override
			public <T> T inTx(java.util.function.Supplier<T> body) {
				return tt.execute(s -> body.get());
			}
		};
	}

	@Bean
	public OrderStateMachine orderStateMachine(OrderStateMachineMapper mapper, OrderStateLogMapper logMapper,
			ApplicationEventPublisher eventPublisher, IdentifierGenerator identifierGenerator, LockRunner orderLockRunner,
			TxRunner orderTxRunner, com.zhiflow.mall.api.mapper.OrderItemMapper orderItemMapper) {
		return new OrderStateMachine(
				new OrderStatePortImpl(mapper, logMapper, eventPublisher, identifierGenerator, orderItemMapper),
				orderLockRunner, orderTxRunner);
	}

	@Bean
	public com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderStateMachine payOrderStateMachine(
			com.zhiflow.mall.api.mapper.PayOrderStateMachineMapper mapper,
			com.zhiflow.mall.api.mapper.PayOrderStateLogMapper logMapper, ApplicationEventPublisher eventPublisher,
			IdentifierGenerator identifierGenerator, LockRunner orderLockRunner, TxRunner orderTxRunner) {
		return new com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderStateMachine(
				new PayOrderStatePortImpl(mapper, logMapper, eventPublisher, identifierGenerator), orderLockRunner,
				orderTxRunner);
	}
}
