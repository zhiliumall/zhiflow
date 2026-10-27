package com.zhiflow.common.security.component;

import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.security.entity.BaseUser;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 压测优化 A2：进程内缓存「令牌内省」的结果 10 秒。<b>开关默认关</b>，关着时与 A1 完全相同。
 *
 * <h2>为什么</h2>
 * <p>生产采样：{@code /mall/admin/notification/count} 81% 的栈在鉴权链上，业务只占 5%。
 * 每个后台请求要从 valkey 读一次约 34 KB 的授权记录（JDK 反序列化）和一次约 99 KB 的
 * {@code public:user_cache}（848 个权限，Jackson 反序列化）。命中本缓存时这两样都省掉，
 * 每请求只剩一次无载荷的 {@code EXISTS}。
 *
 * <h2>语义边界（已确认可接受）</h2>
 * <ul>
 * <li><b>登出 / 踢人 / 撤销设备 / 删角色全员登出 / 令牌过期：仍是下一次请求立即 401。</b>
 *     这些路径都会删直查键 {@code public:token::access_token::<token>}，而每个请求都保留一次
 *     {@code EXISTS}（{@link #getIfAlive}）。</li>
 * <li>改角色菜单、改用户、删用户、租户授权（都会清 {@code public:user_cache}）：经现有频道
 *     {@code cache:invalidate} 广播清各实例（发布端在 common-data 的 {@code DefensiveRedisCache}），
 *     通常毫秒级；广播丢失时<b>最坏晚 10 秒</b>（TTL 兜底）。订阅重连成功时整体作废，
 *     断线期间丢的消息也被兜住。</li>
 * </ul>
 *
 * <h2>缓存值必须只读</h2>
 * <p>多个请求会拿到<b>同一个</b> {@link BaseUser} 实例。放进来之前由调用方 put 好 clientId、
 * 由 {@link #put} 把密码置空（与 Spring Security 认证成功后 {@code eraseCredentials()} 做的事相同，
 * 之后框架再擦一次是 null→null，无害）；放进来之后没有任何代码再改它（全仓 grep：
 * 改 principal attributes 的只有内省器那一处 put，而它发生在放入之前）。
 *
 * <h2>键只能是令牌</h2>
 * <p>同一个用户名可以持有不同客户端的令牌（clientId 不同），所以键是 SHA-256(令牌)，绝不能按用户名。
 * 用摘要而不是令牌原文，是为了不在堆里再多留一份可用的凭证。
 *
 * <h2>容量</h2>
 * <p>按权重限：权重 = 权限个数 + 1。一个 848 权限的 principal 常驻约 104 KB，
 * 上限 {@value #MAX_WEIGHT} ≈ 290 个满权限用户 ≈ 30 MB。有 10 秒写后过期，条目数 ≈ 最近 10 秒内活跃的令牌数，
 * 平时远到不了上限；这个上限只是防极端情况，不能按条数限（1 万条 × 104 KB 在 -Xmx1024M 下不安全）。
 */
@Slf4j
public class AuthorizationSnapshotCache {

	/**
	 * 总开关，默认 false。<b>每次调用现读</b> {@link Environment}，配置改完即时生效，
	 * 关掉即回到 A1（不用重启）。
	 */
	public static final String ENABLED_PROPERTY = "zhiflow.perf.auth-l1.enabled";

	/**
	 * 失效广播频道。与 common-data 的 {@code MultiLevelCacheService.INVALIDATE_CHANNEL} 必须是同一个值
	 * （common-security 不能反向依赖 common-data，只能各存一份；两者相等由 common-data 的单测守）。
	 */
	public static final String INVALIDATE_CHANNEL = "cache:invalidate";

	/** 写后过期。也是「广播丢失时权限变更最多晚多久生效」的上界。 */
	static final Duration TTL = Duration.ofSeconds(10);

	/** 权重上限（权重 = 权限个数 + 1），约 30 MB，见类注释。 */
	static final long MAX_WEIGHT = 250_000L;

	private static final HexFormat HEX = HexFormat.of();

	private final Environment environment;

	private final java.util.concurrent.atomic.AtomicBoolean badFlagWarned = new java.util.concurrent.atomic.AtomicBoolean();

	private final RedisTemplate<String, Object> oauth2RedisTemplate;

	private final Cache<String, Snapshot> cache;

	/**
	 * 每次 {@link #invalidateAll()} 加一。防的是这个竞态：请求 A 读到旧的 user_cache → 广播到达、整体作废 →
	 * A 再把旧值放进来。A 在开始加载前记下代号，放入前后各比一次，变了就不留（见 {@link #put}）。
	 */
	private final AtomicLong epoch = new AtomicLong();

	/**
	 * 一条缓存：令牌那一半（给租户过滤器）+ 内省产出的最终 principal（给内省器）。
	 *
	 * @param tokenTenant 令牌里签发时的租户三元组；过滤器据此每请求现算租户
	 * @param principal 已处理成只读形态的最终 principal，多个请求共用同一个实例
	 */
	public record Snapshot(@Nullable TokenTenant tokenTenant, BaseUser principal) {
	}

	public AuthorizationSnapshotCache(Environment environment, RedisTemplate<String, Object> oauth2RedisTemplate) {
		this(environment, oauth2RedisTemplate, Ticker.systemTicker());
	}

	/** 测试用：可注入假时钟。 */
	AuthorizationSnapshotCache(Environment environment, RedisTemplate<String, Object> oauth2RedisTemplate,
			Ticker ticker) {
		this.environment = environment;
		this.oauth2RedisTemplate = oauth2RedisTemplate;
		this.cache = Caffeine.newBuilder()
			.expireAfterWrite(TTL)
			.maximumWeight(MAX_WEIGHT)
			.weigher((String key, Snapshot value) -> 1 + value.principal().getAuthorities().size())
			.ticker(ticker)
			.build();
	}

	/** 开关现读。 */
	public boolean isEnabled() {
		try {
			return Boolean.TRUE.equals(this.environment.getProperty(ENABLED_PROPERTY, Boolean.class, Boolean.FALSE));
		}
		catch (RuntimeException ex) {
			// 值写错（如 "ture"）时 Environment 抛转换异常；本方法在过滤器里、每个 B 端请求都调，
			// 抛出去就是全服务 500。按关处理，只警告一次
			if (this.badFlagWarned.compareAndSet(false, true)) {
				log.warn("{} 的值无法解析为布尔，按关闭处理: {}", ENABLED_PROPERTY, ex.toString());
			}
			return false;
		}
	}

	/** 缓存键 = SHA-256(令牌) 的十六进制。 */
	public static String keyOf(String token) {
		try {
			return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			// JDK 规范要求每个实现都带 SHA-256，走不到这里
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * 取条目，并确认令牌仍在 Redis 里；不在了就作废这条、返回 null（调用方走原逻辑，得到与今天一样的 401）。
	 *
	 * @param knownExists 本请求里已经知道的存活结果（过滤器查过 / 读到过记录）；null 表示不知道，
	 * 此时本方法自己查一次 {@code EXISTS}。条目不存在时不查。
	 */
	@Nullable
	public Snapshot getIfAlive(String key, String token, @Nullable Boolean knownExists) {
		Snapshot snapshot = this.cache.getIfPresent(key);
		if (snapshot == null) {
			return null;
		}
		boolean alive = knownExists != null ? knownExists : tokenExists(token);
		if (!alive) {
			this.cache.invalidate(key);
			return null;
		}
		return snapshot;
	}

	/** 在开始加载（读授权记录 / user_cache）之前调用，把返回值交给 {@link #put}。 */
	public long epoch() {
		return this.epoch.get();
	}

	/**
	 * 放入一条。放入前把 principal 的密码置空；从此它只读。
	 *
	 * <p>调用方负责：clientId 已经 put 进 attributes；账号未锁定（与 user_cache 自己的写入策略一致，
	 * 被锁的用户每请求回源）。
	 *
	 * @param epochAtLoad 加载开始前 {@link #epoch()} 的值；期间发生过整体作废就不留这条
	 */
	public void put(String key, long epochAtLoad, @Nullable TokenTenant tokenTenant, BaseUser principal) {
		if (this.epoch.get() != epochAtLoad) {
			return;
		}
		principal.eraseCredentials();
		this.cache.put(key, new Snapshot(tokenTenant, principal));
		// 放入与作废交错：作废发生在上面那次比较之后、put 之前时，这里补删
		if (this.epoch.get() != epochAtLoad) {
			this.cache.invalidate(key);
		}
	}

	public void invalidate(String key) {
		this.cache.invalidate(key);
	}

	/** 整体作废（收到用户 / 令牌缓存的失效广播、订阅重连成功时）。 */
	public void invalidateAll() {
		this.epoch.incrementAndGet();
		this.cache.invalidateAll();
	}

	/**
	 * {@code EXISTS public:token::access_token::<token>}：无载荷，一个往返。
	 * 所有吊销路径（登出、踢人、撤销设备、删角色全员登出）以及自然过期都会让它变成 false。
	 *
	 * <p>Redis 不可用时异常照常抛出 —— 改造前同一位置的 GET 也会抛，行为一致。
	 */
	public boolean tokenExists(String token) {
		return Boolean.TRUE.equals(this.oauth2RedisTemplate.hasKey(directAccessTokenKey(token)));
	}

	/** 与 {@code BaseRedisOAuth2AuthorizationService} 写的直查键、过滤器读的键是同一个。 */
	static String directAccessTokenKey(String token) {
		return CacheConstants.OAUTH_CLIENT_CACHE + "::" + token;
	}

	/** 测试用。 */
	long estimatedSize() {
		this.cache.cleanUp();
		return this.cache.estimatedSize();
	}

}
