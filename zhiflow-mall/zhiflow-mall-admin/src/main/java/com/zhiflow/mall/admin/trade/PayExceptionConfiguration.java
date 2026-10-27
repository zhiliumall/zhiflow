package com.zhiflow.mall.admin.trade;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.zhiflow.mall.common.trade.payexception.PayExceptionAlertSink;
import com.zhiflow.mall.common.trade.payexception.PayExceptionLoggedDedupe;
import com.zhiflow.mall.common.trade.payexception.PayExceptionRecorder;
import com.zhiflow.mall.admin.mapper.PayExceptionMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 差错池入池器的装配（支付与订单状态机 v1.2 第六章）。
 *
 * <p>逻辑在 mall-common（{@link PayExceptionRecorder}），这里只把本服务的 mapper、事务管理器、
 * 告警出口、指标注册表接上。{@code IdentifierGenerator} 是 mall-common 自动配置给的注册表机器位雪花。
 * 不进表类型的去重接 Redis（{@code SET NX EX}）：两台实例、重启前后都只报一次。
 */
@Configuration(proxyBeanMethods = false)
public class PayExceptionConfiguration {

	static final String LOGGED_KEY = "pay:exception:logged:";

	@Bean
	public PayExceptionRecorder payExceptionRecorder(PayExceptionMapper mapper,
			IdentifierGenerator identifierGenerator,
			ObjectProvider<PlatformTransactionManager> transactionManager,
			ObjectProvider<PayExceptionAlertSink> alertSinks,
			ObjectProvider<MeterRegistry> meterRegistry,
			ObjectProvider<StringRedisTemplate> redis) {
		return new PayExceptionRecorder(mapper::insertIfAbsent, identifierGenerator,
				transactionManager.getIfAvailable(), alertSinks.orderedStream().toList(),
				meterRegistry.getIfAvailable(), loggedDedupe(redis.getIfAvailable()));
	}

	static PayExceptionLoggedDedupe loggedDedupe(StringRedisTemplate redis) {
		if (redis == null) {
			return PayExceptionLoggedDedupe.inMemory(PayExceptionRecorder.LOGGED_DEDUPE_WINDOW, System::currentTimeMillis);
		}
		// setIfAbsent 返回 null（事务 / 管道里）按首次处理；抛异常由入池器兜成「首次」——宁可重复告警，不可静默
		return key -> !Boolean.FALSE.equals(redis.opsForValue()
				.setIfAbsent(LOGGED_KEY + key, "1", PayExceptionRecorder.LOGGED_DEDUPE_WINDOW));
	}
}
