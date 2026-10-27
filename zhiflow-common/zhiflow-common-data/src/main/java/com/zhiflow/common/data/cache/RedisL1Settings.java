package com.zhiflow.common.data.cache;

import com.zhiflow.common.core.constant.CacheConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 哪些缓存名要加进程内 L1（{@link TwoLevelRedisCache}）。
 *
 * <pre>
 * zhiflow.perf.redis-l1.cache-names: public:menu_cache   # 逗号分隔或 YAML 列表；默认空 = 哪里都没有 L1
 * </pre>
 *
 * <p>名单里写的是<b>不带租户前缀</b>的缓存名（与 {@code @Cacheable} 上写的一致，如 {@code mall:goods_detail#600}）；
 * 缓存管理器给非 {@code public:} 缓存加的 {@code {tenantId}:} 前缀不参与比较，L1 仍按带前缀的名字每租户一份。
 *
 * <p><b>动态</b>：{@code RedisAutoCacheManager#getCache} 每次取缓存都问一遍这里，本类最多每秒重读一次
 * {@link Environment}（用 {@link Binder}，列表的两种写法都认）。配置改完约 1 秒内生效，开和关都不用重启。
 * 之所以不在 {@code decorateCache} 里决定：那个钩子只在缓存<b>创建时</b>跑一次（{@code cache-names} 里列的
 * 那批在启动时就创建了），开关就只能靠重启 upms 生效——而 upms 一重启，所有调用方对它的内部调用都会断一下。
 *
 * <p>硬性排除见 {@link #DENIED}。
 */
@Slf4j
final class RedisL1Settings {

	static final String CACHE_NAMES_PROPERTY = "zhiflow.perf.redis-l1.cache-names";

	/**
	 * 不许走这条路径加 L1 的缓存名：
	 * <ul>
	 * <li>{@code public:user_cache} 与令牌缓存：{@code BaseUser} 读出来之后每请求都会被改（put clientId），不能跨请求共用；
	 *     这两份的进程内缓存由 common-security 的内省缓存（A2）负责；</li>
	 * <li>{@code mall:goods_stock#5}：只靠 5 秒 TTL 自动过期，过期不经过任何清除、代号标记感知不到，
	 *     进 L1 会显示最长 L1 TTL 的旧库存。</li>
	 * </ul>
	 */
	static final Set<String> DENIED = Set.of(CacheConstants.USER_CACHE, CacheConstants.OAUTH_CLIENT_CACHE,
			CacheConstants.MALL_GOODS_STOCK_CACHE);

	/**
	 * 允许加 L1 的缓存名（白名单）及各自的容量策略。进程内 L1 会让同一个对象被多个请求共用，
	 * 只有审计过「读出来后没人改」的缓存才能进；要加别的名字先审计其全部读方，再加到这里。
	 * <ul>
	 * <li>{@code public:menu_cache}：2026-09-30 审计；</li>
	 * <li>{@code mall:goods_detail#600}：2026-10-23 审计（读方只经 {@code toGoodsSpu / toSpecTree} 转出新对象，
	 *     见 mall-api {@code GoodsDetailSnapshot}）。</li>
	 * </ul>
	 * 白名单里的名字，<b>不论本进程开没开 L1</b>，清除时都会删代号标记（{@link DefensiveRedisCache}）：
	 * 读的进程开着 L1、清的进程没开时，读方也能感知。
	 */
	static final Map<String, TwoLevelRedisCache.Policy> ALLOWED = Map.of(
			CacheConstants.MENU_CACHE, TwoLevelRedisCache.Policy.BY_ELEMENTS,
			CacheConstants.MALL_GOODS_DETAIL_CACHE, TwoLevelRedisCache.Policy.BY_KB);

	private static final long REFRESH_NANOS = TimeUnit.SECONDS.toNanos(1);

	private final Environment environment;

	private final long refreshNanos;

	/** 每个被排除的名字只警告一次（本类每秒重读一次配置，不去重会刷屏）。 */
	private final Set<String> warnedDenied = ConcurrentHashMap.newKeySet();

	private volatile Snapshot snapshot;

	private record Snapshot(Set<String> names, long readAtNanos) {
	}

	RedisL1Settings(Environment environment) {
		this(environment, REFRESH_NANOS);
	}

	/** 测试用：可把重读间隔设成 0（每次都读）。 */
	RedisL1Settings(Environment environment, long refreshNanos) {
		this.environment = environment;
		this.refreshNanos = refreshNanos;
		this.snapshot = new Snapshot(read(), System.nanoTime());
	}

	/** @param cacheName 不带租户前缀的缓存名 */
	boolean isEnabledFor(String cacheName) {
		Snapshot current = this.snapshot;
		long now = System.nanoTime();
		if (now - current.readAtNanos() >= this.refreshNanos) {
			// 并发时可能有几个线程同时重读，结果相同，无害
			current = new Snapshot(read(), now);
			this.snapshot = current;
		}
		return current.names().contains(cacheName);
	}

	/**
	 * 实际缓存名（可能带 {@code {tenantId}:} 前缀）是否在白名单里：先按原样比，再去掉第一段前缀比。
	 * {@code public:menu_cache} 原样命中；{@code 1:mall:goods_detail#600} 去掉 {@code 1:} 后命中。
	 */
	static boolean allowsL1(String cacheName) {
		return policyOf(cacheName) != null;
	}

	/** 同 {@link #allowsL1}，返回策略；不在白名单返回 null。 */
	static TwoLevelRedisCache.Policy policyOf(String cacheName) {
		if (cacheName == null) {
			return null;
		}
		TwoLevelRedisCache.Policy policy = ALLOWED.get(cacheName);
		if (policy != null) {
			return policy;
		}
		int colon = cacheName.indexOf(':');
		return colon < 0 ? null : ALLOWED.get(cacheName.substring(colon + 1));
	}

	private Set<String> read() {
		List<String> configured;
		try {
			configured = Binder.get(this.environment)
				.bind(CACHE_NAMES_PROPERTY, Bindable.listOf(String.class))
				.orElse(List.of());
		}
		catch (RuntimeException ex) {
			log.warn("读取 {} 失败，按空处理（不加 L1）: {}", CACHE_NAMES_PROPERTY, ex.toString());
			return Set.of();
		}
		Set<String> names = new LinkedHashSet<>();
		for (String name : configured) {
			String trimmed = name == null ? "" : name.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (DENIED.contains(trimmed) || !ALLOWED.containsKey(trimmed)) {
				if (this.warnedDenied.add(trimmed)) {
					log.warn("{} 里的 {} 不允许加进程内 L1（未审计 / 值读出来后会被改 / 只靠 TTL 过期），已忽略",
							CACHE_NAMES_PROPERTY, trimmed);
				}
				continue;
			}
			names.add(trimmed);
		}
		return Set.copyOf(names);
	}

}
