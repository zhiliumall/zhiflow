package com.zhiflow.common.security.component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 压测优化 A2 的装配：内省缓存 + {@code cache:invalidate} 订阅者。
 *
 * <h2>为什么挂在 {@code @EnableBaseResourceServer} 上，而不是 {@link BaseResourceServerAutoConfiguration}</h2>
 * <p>auth 服务也 {@code @Import(BaseResourceServerAutoConfiguration)}（{@code ZhiflowAuthApplication}），
 * 但它的安全链不用内省器（授权服务器 + 表单登录），挂在那里只会给 auth 白白多开一条订阅连接。
 * 挂在 {@code @EnableBaseResourceServer} 的 {@code @Import} 里，只有资源服务会有，单体 {@code zhiflow-boot}
 * 的多个装配类也只会导入一次。每个资源服务实例在 {@code cache:invalidate} 上各有一个订阅
 * （可用 {@code PUBSUB NUMSUB cache:invalidate} 核对）。
 *
 * <p>本类刻意<b>不</b>加 {@code @Configuration}：本包被 {@code BaseResourceServerAutoConfiguration}
 * 的 {@code @ComponentScan} 扫描，加了就会连 auth 也扫进去。
 */
@Slf4j
public class AuthorizationSnapshotConfiguration {

	/** 内省缓存本身。开关 {@value AuthorizationSnapshotCache#ENABLED_PROPERTY} 默认关，每请求现读。 */
	@Bean
	public AuthorizationSnapshotCache authorizationSnapshotCache(Environment environment,
			@Qualifier("oauth2RedisTemplate") RedisTemplate<String, Object> oauth2RedisTemplate) {
		return new AuthorizationSnapshotCache(environment, oauth2RedisTemplate);
	}

	/**
	 * 订阅者。<b>不随内省缓存开关变化，始终注册</b>：开关是按服务、现读的，
	 * 订阅得一直在，开关一打开就能立刻正确失效。
	 *
	 * <p>与 {@code CacheInvalidateListenerConfig} 共用同一个关闭开关
	 * {@code zhiflow.cache.invalidate-listener.enabled}（默认开）：两者订阅的是同一个频道，
	 * 而那个开关的用途（冒烟门禁里挡外部连接）对这里同样成立。关掉它时内省缓存只剩 10 秒 TTL 兜底。
	 */
	@Bean
	@ConditionalOnProperty(name = "zhiflow.cache.invalidate-listener.enabled", matchIfMissing = true)
	public RedisMessageListenerContainer authorizationSnapshotInvalidationContainer(
			RedisConnectionFactory connectionFactory, AuthorizationSnapshotCache authorizationSnapshotCache) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		// 默认 SimpleAsyncTaskExecutor 每条消息新建一个平台线程（同频道的业务消息也算）；换成单线程有界队列
		org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
				new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(10_000);
		executor.setThreadNamePrefix("auth-l1-invalidate-");
		executor.setDaemon(true);
		executor.initialize();
		container.setTaskExecutor(executor);
		container.addMessageListener(new AuthorizationSnapshotInvalidationListener(authorizationSnapshotCache),
				new ChannelTopic(AuthorizationSnapshotCache.INVALIDATE_CHANNEL));
		log.info("内省缓存失效订阅已注册 channel={}（开关 {} 现读，默认关）", AuthorizationSnapshotCache.INVALIDATE_CHANNEL,
				AuthorizationSnapshotCache.ENABLED_PROPERTY);
		return container;
	}

}
