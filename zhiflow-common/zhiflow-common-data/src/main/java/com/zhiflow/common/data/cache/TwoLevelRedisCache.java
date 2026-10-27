package com.zhiflow.common.data.cache;

import com.zhiflow.common.core.util.R;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * 压测优化 C-L1：在 {@link DefensiveRedisCache}（L2）前面加一层进程内 Caffeine（L1）。
 * 只对 {@link RedisL1Settings#CACHE_NAMES_PROPERTY} 里列出的缓存名生效，默认一个都没有。
 *
 * <h2>为什么</h2>
 * <p>upms 的 {@code /user/info}、{@code /menu} 每请求要从 {@code public:menu_cache} 反序列化约 593 KB 的菜单 JSON
 * （超管 1731 个 MenuVO）。生产线程转储里这两个接口 67~68% 的栈在「缓存命中后的 Jackson 反序列化」上。
 * L1 命中时直接返回上次反序列化出来的对象。
 *
 * <h2>跨实例失效：代号标记（不是 pub/sub）</h2>
 * <p>同一前缀下放一个小键 {@code <缓存名>::__l1_gen}，值是一个随机代号。每条 L1 记着自己是哪个代号下读的，
 * <b>每次 L1 命中都用一次很小的 GET 核对当前代号</b>，不同就当没命中。任何让缓存内容变化的操作都会让代号消失：
 * <ul>
 * <li>现有的 {@code clear()} / {@code @CacheEvict(allEntries = true)}：按 {@code <缓存名>::*} 删，标记在同一前缀下，一起没了；</li>
 * <li>运维按 {@code public:menu_cache::*} 手工批量删：同上（pub/sub 覆盖不到这条路径，这是选标记的主要理由）；</li>
 * <li>任何经过 {@link DefensiveRedisCache} 的 evict / evictIfPresent / clear / invalidate（白名单缓存名）：
 *     先删 L2 数据、再 DEL 标记，<b>不论清的那个进程开没开 L1</b>（多实例或拆分部署时，清的实例与读的实例不一定是同一个）；
 *     经过本类时最后再清本机 L1。</li>
 * </ul>
 * 顺序很关键：先删 L2 再删标记，别的实例在两步之间即使读到新代号，去 L2 也已经读不到旧值。
 * 标记没了，下一个来读的实例 SET NX 一个新代号，各实例在下一次访问时就都能发现。
 * 标记按缓存名（每租户一份）一个：清任何一个键，该租户这份缓存的全部 L1 都作废一轮（只是多读一次 L2，不回库）。
 *
 * <h2>Redis 不可用</h2>
 * <p>读不到代号就绕过 L1，完全退回原行为（L2 出错由 {@code CacheFailureToleranceConfiguration} 按未命中处理）。
 * 宁可慢，也不给出无法核对的旧值。
 *
 * <h2>最坏情况（已知、接受）</h2>
 * <ul>
 * <li>运维只 DEL 了某一个键、没删标记：其它实例的这一条最多旧到 L1 过期（{@link Policy#ttl()}）；</li>
 * <li>用 {@code put}（{@code @CachePut}）覆盖已有值<b>不</b>换代号：{@code put} 在这里只是 {@code @Cacheable}
 *     未命中后的回填。要做「更新」的缓存请用 evict，或者别给它开 L1。{@code public:menu_cache} 没有 {@code @CachePut}。</li>
 * </ul>
 *
 * <h2>值必须只读</h2>
 * <p>命中时多个请求拿到<b>同一个对象</b>。只给读出来之后没人改的缓存开：{@code public:menu_cache} 已逐个调用点核过。
 * {@code public:user_cache}（{@code BaseUser} 每请求被 put clientId）
 * 与令牌缓存被 {@link RedisL1Settings} 硬性排除。{@code put} 进来的活对象（调用方手里也有一份）不进 L1，
 * 下一次从 L2 读出的新副本才进。
 */
@Slf4j
final class TwoLevelRedisCache implements Cache {

	/** 代号标记在缓存前缀下的键名。 */
	static final String GENERATION_KEY = "__l1_gen";

	/** 标记本身的 TTL：过期后下一次访问生成新代号，全部 L1 miss 一轮，无害；只是防功能下线后留下孤儿键。 */
	private static final Duration GENERATION_TTL = Duration.ofDays(1);

	/**
	 * 每个白名单缓存名的 L1 容量与过期（见 {@link RedisL1Settings#ALLOWED}）。
	 * 写后过期只是内存兜底与「绕过清除直接改库 / 单键手工删除」的上界，不负责一致性（一致性靠代号）。
	 *
	 * @param maxWeight 权重上限（每个缓存名、即每租户一份各自计）
	 * @param ttl       写后过期
	 * @param weigher   单条权重
	 */
	record Policy(long maxWeight, Duration ttl, ToIntFunction<Object> weigher) {

		/**
		 * 按「元素个数」限容量，不按条数：一条 {@code public:menu_cache} 可以大到约 0.7 MB（上千个 MenuVO），
		 * 条数上限挡不住内存。权重 = 集合元素个数（{@code R} 里包着集合时取其元素数）+ 1；
		 * 一个 MenuVO 常驻约 0.6 KB ⇒ 上限约 30 MB；几十个角色、每个一千多个菜单全装进来约 18 MB。
		 */
		static final Policy BY_ELEMENTS = new Policy(50_000L, Duration.ofMinutes(10), TwoLevelRedisCache::weigh);

		/**
		 * 按值自报的近似 KB（{@link L1Weighted}）计权，上限约 32 MB；没实现接口的值按元素数计。
		 * 写后 5 分钟：后台改商品都经提交后清除（代号即时生效），这个时长只决定「有人绕过后台直接改库」时最长旧多久
		 * （L2 剩余 TTL + 5 分钟）。商品详情快照见 mall-api {@code GoodsDetailSnapshot#l1WeightKb}。
		 */
		static final Policy BY_KB = new Policy(32_768L, Duration.ofMinutes(5), TwoLevelRedisCache::weighKb);
	}

	private final DefensiveRedisCache l2;

	private final RedisCacheWriter writer;

	private final byte[] generationKey;

	private final com.github.benmanes.caffeine.cache.Cache<Object, Entry> l1;

	private final Policy policy;

	/** 一条 L1：读它时的代号 + 读到的值（同一个 ValueWrapper 实例反复返回）。 */
	private record Entry(byte[] generation, ValueWrapper value) {
	}

	/** 测试用：按菜单缓存的策略。 */
	TwoLevelRedisCache(DefensiveRedisCache l2) {
		this(l2, Policy.BY_ELEMENTS);
	}

	TwoLevelRedisCache(DefensiveRedisCache l2, Policy policy) {
		this.l2 = l2;
		this.policy = policy;
		this.writer = l2.getNativeCache();
		this.generationKey = l2.l1GenerationKey();
		this.l1 = Caffeine.newBuilder()
			.maximumWeight(policy.maxWeight())
			.weigher((Object key, Entry entry) -> policy.weigher().applyAsInt(entry.value().get()))
			.expireAfterWrite(policy.ttl())
			.build();
	}

	/** 近似占用：集合按元素数；{@code R} 里包着集合按其元素数（只数顶层，all_tree 这一条会偏小，无妨）。 */
	static int weigh(@Nullable Object value) {
		Object payload = value instanceof R<?> r ? r.getData() : value;
		if (payload instanceof Collection<?> collection) {
			return 1 + collection.size();
		}
		return 1;
	}

	/** 值自报 KB（{@link L1Weighted}）；没实现的退回按元素数。 */
	static int weighKb(@Nullable Object value) {
		if (value instanceof L1Weighted weighted) {
			return Math.max(1, weighted.l1WeightKb());
		}
		return weigh(value);
	}

	@Override
	public String getName() {
		return this.l2.getName();
	}

	@Override
	public Object getNativeCache() {
		return this.l2.getNativeCache();
	}

	@Override
	@Nullable
	public ValueWrapper get(Object key) {
		byte[] generation = currentGeneration();
		if (generation == null) {
			return this.l2.get(key);
		}
		Entry entry = this.l1.getIfPresent(key);
		if (entry != null && Arrays.equals(entry.generation(), generation)) {
			return entry.value();
		}
		// 代号是在读 L2 之前取的：两步之间有人清了缓存，这条就带着旧代号进 L1，下次访问自然失效
		ValueWrapper value = this.l2.get(key);
		if (value != null) {
			this.l1.put(key, new Entry(generation, value));
		}
		return value;
	}

	@Override
	@Nullable
	@SuppressWarnings("unchecked")
	public <T> T get(Object key, @Nullable Class<T> type) {
		ValueWrapper wrapper = get(key);
		Object value = wrapper != null ? wrapper.get() : null;
		// 与 AbstractValueAdaptingCache#get(Object, Class) 的类型检查一致
		if (value != null && type != null && !type.isInstance(value)) {
			throw new IllegalStateException(
					"Cached value is not of required type [" + type.getName() + "]: " + value);
		}
		return (T) value;
	}

	@Override
	@Nullable
	@SuppressWarnings("unchecked")
	public <T> T get(Object key, Callable<T> valueLoader) {
		ValueWrapper cached = get(key);
		if (cached != null) {
			return (T) cached.get();
		}
		// L2 也没有：交给 L2 加载并写入。加载出来的是调用方手里的活对象，不进 L1（见类注释）
		return this.l2.get(key, valueLoader);
	}

	@Override
	@Nullable
	public CompletableFuture<?> retrieve(Object key) {
		return this.l2.retrieve(key);
	}

	@Override
	public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
		return this.l2.retrieve(key, valueLoader);
	}

	/** {@code @Cacheable} 未命中后的回填。不换代号（见类注释「最坏情况」第三条）。 */
	@Override
	public void put(Object key, @Nullable Object value) {
		this.l2.put(key, value);
		this.l1.invalidate(key);
	}

	@Override
	@Nullable
	public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
		ValueWrapper existing = this.l2.putIfAbsent(key, value);
		this.l1.invalidate(key);
		return existing;
	}

	@Override
	public void evict(Object key) {
		this.l2.evict(key);
		this.l1.invalidate(key);
		dropGenerationIfL2DoesNot();
	}

	@Override
	public boolean evictIfPresent(Object key) {
		// 不管 L2 里有没有：别的实例的 L1 可能还留着这条（L2 已按 TTL 过期），照样要让它们失效（L2 里删代号也不看有没有）
		boolean evicted = this.l2.evictIfPresent(key);
		this.l1.invalidate(key);
		dropGenerationIfL2DoesNot();
		return evicted;
	}

	@Override
	public void clear() {
		this.l2.clear();
		this.l1.invalidateAll();
		dropGenerationIfL2DoesNot();
	}

	@Override
	public boolean invalidate() {
		boolean invalidated = this.l2.invalidate();
		this.l1.invalidateAll();
		dropGenerationIfL2DoesNot();
		return invalidated;
	}

	/**
	 * 当前代号：GET 标记；没有就 SET NX 一个随机值（并发时以先写成的为准）。
	 * Redis 出错返回 null，调用方据此绕过 L1。直接用 {@link RedisCacheWriter} 按原始字节读写，不经过缓存的反序列化。
	 */
	@Nullable
	private byte[] currentGeneration() {
		try {
			byte[] generation = this.writer.get(getName(), this.generationKey);
			if (generation != null) {
				return generation;
			}
			byte[] candidate = this.l2.l1GenerationValue(UUID.randomUUID().toString());
			byte[] existing = this.writer.putIfAbsent(getName(), this.generationKey, candidate, GENERATION_TTL);
			return existing != null ? existing : candidate;
		}
		catch (RuntimeException ex) {
			log.debug("Cache[{}] 读 L1 代号失败，本次绕过 L1: {}", getName(), ex.toString());
			return null;
		}
	}

	/**
	 * 白名单缓存名的 L2 在清除时已经删了代号（{@link DefensiveRedisCache#dropsL1Generation()}）；
	 * 只有包着非白名单 L2（只在测试里出现）时才由本类补删，保持旧行为。
	 */
	private void dropGenerationIfL2DoesNot() {
		if (!this.l2.dropsL1Generation()) {
			this.l2.dropL1Generation();
		}
	}

	/** 是否包着这个 L2 实例。 */
	boolean wraps(DefensiveRedisCache candidate) {
		return this.l2 == candidate;
	}

	/** 测试用。 */
	long localSize() {
		this.l1.cleanUp();
		return this.l1.estimatedSize();
	}

}
