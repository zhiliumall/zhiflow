package com.zhiflow.common.security.component;

import com.zhiflow.common.core.constant.CacheConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * 订阅 {@code cache:invalidate}：用户 / 令牌缓存一被清，就整体作废本进程的 {@link AuthorizationSnapshotCache}。
 *
 * <h2>消息格式</h2>
 * <p>发布端（common-data 的 {@code DefensiveRedisCache}）与现有的 {@code MultiLevelCacheService.evict}
 * 用同一个 {@code redisTemplate.convertAndSend}，消息体是主 RedisTemplate 的 JSON 序列化器写出的字符串，
 * 形如 {@code "public:user_cache::admin"} / {@code "public:user_cache::*"}（带 JSON 引号）。
 * 这里不做反序列化，直接按 UTF-8 文本判断是否提到这两个缓存名：
 * common-security 拿不到那个序列化器（在 common-data），而且判「提没提到」对编码细节不敏感 ——
 * 误判成「提到了」的代价只是多作废一次。同一频道上 {@code MultiLevelCacheService} 的业务缓存消息一律忽略。
 *
 * <h2>为什么收到就整体作废，而不是按用户名删</h2>
 * <p>缓存按令牌摘要做键，值里才有用户名；而 user_cache 的键不总是用户名（{@code loadUserByPhone}
 * 以手机号为键写入）。按消息里的键去匹配用户名会漏；整体作废最多让最近 10 秒的活跃令牌各回源一次，
 * 而清 user_cache 的事件（改角色 / 改用户 / 登出）一天也没几次。
 *
 * <h2>丢消息兜底</h2>
 * <p>Redis 发布订阅是「至多一次」。本类同时实现 {@link SubscriptionListener}：每次（重新）订阅成功都整体作废，
 * 把「断线期间丢了消息」这个窗口也关上。剩下「连接看着正常、消息却没到」的极端情况由 10 秒 TTL 兜底。
 */
@Slf4j
public class AuthorizationSnapshotInvalidationListener implements MessageListener, SubscriptionListener {

	private final AuthorizationSnapshotCache cache;

	public AuthorizationSnapshotInvalidationListener(AuthorizationSnapshotCache cache) {
		this.cache = cache;
	}

	@Override
	public void onMessage(Message message, @Nullable byte[] pattern) {
		byte[] body = message.getBody();
		if (body == null || !affectsIntrospection(body)) {
			return;
		}
		this.cache.invalidateAll();
		log.debug("收到用户/令牌缓存失效广播，已整体作废内省缓存");
	}

	@Override
	public void onChannelSubscribed(byte[] channel, long count) {
		// 首次订阅和断线重连后的重新订阅都会走到这里：断线期间可能丢了消息，一律整体作废
		this.cache.invalidateAll();
		log.info("内省缓存失效订阅已就绪 channel={}，已整体作废本地内省缓存",
				new String(channel, StandardCharsets.UTF_8));
	}

	/** 消息是否提到了 {@code public:user_cache} 或 {@code public:token::access_token}。 */
	static boolean affectsIntrospection(byte[] body) {
		String text = new String(body, StandardCharsets.UTF_8);
		return text.contains(CacheConstants.USER_CACHE) || text.contains(CacheConstants.OAUTH_CLIENT_CACHE);
	}

}
