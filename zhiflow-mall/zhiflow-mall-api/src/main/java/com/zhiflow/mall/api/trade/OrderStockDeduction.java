package com.zhiflow.mall.api.trade;

import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.cache.AfterCommitCacheEviction;
import com.zhiflow.mall.api.mapper.GoodsSkuMapper;
import com.zhiflow.mall.common.entity.OrderItem;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.core.env.Environment;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 下单扣库存（10-18 压测 B1：热点 SKU 等锁上限 + 固定加锁顺序）。
 *
 * <p>扣库存是带 {@code stock >= qty} 守卫的单行原子 UPDATE（{@link GoodsSkuMapper#decreaseStock}），行锁一直攥到下单事务提交。
 * 热点商品上，排在后面的下单请求原来会一直等到事务超时（5 秒），最后回 500。开关打开后：
 * <ul>
 *   <li><b>等锁上限</b>：扣库存前 {@code SET LOCAL lock_timeout}（默认 2 秒），扣完立即恢复成连接默认值——只收紧这几条 UPDATE，
 *       事务里其它锁等待（身份证 advisory lock、秒杀已售数、券核销）不受影响。等不到 = PG 55P03，转成
 *       {@link StockBusyException}（「商品太火爆，请稍后再试」），整单回滚、防重 token 由下单接口释放。</li>
 *   <li><b>死锁转 70008</b>：万一撞死锁（别的路径反序拿了同一行），同样转成 {@link StockBusyException}。</li>
 * </ul>
 *
 * <p><b>加锁顺序与开关无关（2026-10-26 起恒开）</b>：按 skuId 升序逐条扣（每条 UPDATE 只锁自己那一行，
 * 语句顺序就是加锁顺序）；多店铺下单按店铺 id 升序处理（{@link #lockOrder}），合起来是全局一致的（店铺, SKU）顺序。
 * 原来跟着客户端提交顺序，两单对同两个 SKU 反序提交（A,B / B,A）会互等成死锁（PG 40P01，等 deadlock_timeout 才被裁掉一个）。
 * 取消回补库存也按同一顺序（{@code OrderInfoServiceImpl#restoreStockInLockOrder} / {@link StockHoldService#restoreOnCancel}）。
 *
 * <p>开关 {@value #ENABLED_KEY}（默认关）、时长 {@value #TIMEOUT_MS_KEY}（毫秒，默认 2000），每单现读，配置改了下一单即生效。
 * 开关关着：不发任何额外语句（不设 lock_timeout）、锁异常照旧抛给全局处理器；只有顺序是固定的。
 *
 * <p>事务超时（{@code ORDER_TX_TIMEOUT_SECONDS} = 5 秒）仍是外层上限：剩余时间不足等锁上限时，先到的是事务超时（57014），走原有处理。
 *
 * <p><b>售罄 / 低库存即时清详情库存缓存</b>（2026-10-23，与上面的开关无关）：扣减语句带回剩余库存（{@code RETURNING stock}），
 * 某 SKU 扣后剩余 ≤ {@value #LOW_STOCK_THRESHOLD_KEY}（默认 {@value #DEFAULT_LOW_STOCK_THRESHOLD}；0 = 只在售罄时；负数 = 关），
 * 就在下单事务<b>提交后</b>清该商品的 {@code mall:goods_stock#5}，详情页立即显示新库存而不是最多晚 5 秒。
 * 只在低库存时清：库存充足时照旧靠 5 秒 TTL，热门商品不会被每一单击穿；每个商品从进入阈值到售罄最多再清「阈值 + 1」次。
 * 回滚不清；登记或清除失败只记日志，不影响下单结果。取消 / 退款回补库存（库存变多）不清。
 */
@Slf4j
@Component
public class OrderStockDeduction {

	public static final String ENABLED_KEY = "zhiflow.perf.order-lock-timeout.enabled";

	public static final String TIMEOUT_MS_KEY = "zhiflow.perf.order-lock-timeout.ms";

	public static final long DEFAULT_TIMEOUT_MS = 2000;

	public static final String LOW_STOCK_THRESHOLD_KEY = "zhiflow.perf.goods-stock-evict.threshold";

	public static final int DEFAULT_LOW_STOCK_THRESHOLD = 5;

	/** 抢不到行锁的次数，reason = lock_timeout（55P03）/ deadlock（40P01）/ other。 */
	static final String BUSY_METRIC = "order_stock_lock_busy_total";

	/** PG lock_not_available：等锁超过 lock_timeout。 */
	static final String SQLSTATE_LOCK_NOT_AVAILABLE = "55P03";

	/** PG deadlock_detected。 */
	static final String SQLSTATE_DEADLOCK_DETECTED = "40P01";

	/** 服务里没注入本类时（单测按类型注入、未提供）用它：开关恒关、不清缓存；加锁顺序照样固定。 */
	public static final OrderStockDeduction DISABLED = new OrderStockDeduction(null);

	/** 下单扣减与取消回补共用的加锁顺序：skuId 字符串自然序。 */
	public static final Comparator<OrderItem> LOCK_ORDER = Comparator.comparing(OrderItem::getSkuId,
			Comparator.nullsLast(Comparator.naturalOrder()));

	private final Environment env;

	/** null = 不清库存缓存（单测、{@link #DISABLED}）。 */
	private final CacheManager cacheManager;

	public OrderStockDeduction(Environment env) {
		this(env, null);
	}

	@Autowired
	public OrderStockDeduction(Environment env, CacheManager cacheManager) {
		this.env = env;
		this.cacheManager = cacheManager;
	}

	/** 开关现读。配错格式（转不成布尔）按关处理：每单都读它，抛出去就是全站下单 500。 */
	public boolean enabled() {
		if (env == null) {
			return false;
		}
		try {
			return Boolean.TRUE.equals(env.getProperty(ENABLED_KEY, Boolean.class, Boolean.FALSE));
		}
		catch (RuntimeException e) {
			log.warn("{} 配置无法解析，按关处理：{}", ENABLED_KEY, e.getMessage());
			return false;
		}
	}

	/**
	 * 等锁上限（毫秒）。没配、配成非正数、超过 PG lock_timeout 上限（int 毫秒）或配错格式都按默认 2000——
	 * 这个值配错不该让下单失败（超上限时 set_config 会报错、整单回滚）。
	 */
	long lockTimeoutMs() {
		try {
			Long ms = env.getProperty(TIMEOUT_MS_KEY, Long.class, DEFAULT_TIMEOUT_MS);
			return ms == null || ms <= 0 || ms > Integer.MAX_VALUE ? DEFAULT_TIMEOUT_MS : ms;
		}
		catch (RuntimeException e) {
			log.warn("{} 配置无法解析，按默认 {}ms：{}", TIMEOUT_MS_KEY, DEFAULT_TIMEOUT_MS, e.getMessage());
			return DEFAULT_TIMEOUT_MS;
		}
	}

	/**
	 * 低库存阈值：扣后剩余 ≤ 它就提交后清该商品的库存缓存；0 = 只在售罄时；负数 = 关。
	 * 没配或配错格式按默认 5（每单都读它，抛出去就是全站下单 500）。
	 */
	int lowStockThreshold() {
		if (env == null) {
			return -1;
		}
		try {
			Integer threshold = env.getProperty(LOW_STOCK_THRESHOLD_KEY, Integer.class, DEFAULT_LOW_STOCK_THRESHOLD);
			return threshold == null ? DEFAULT_LOW_STOCK_THRESHOLD : threshold;
		}
		catch (RuntimeException e) {
			log.warn("{} 配置无法解析，按默认 {}：{}", LOW_STOCK_THRESHOLD_KEY, DEFAULT_LOW_STOCK_THRESHOLD, e.getMessage());
			return DEFAULT_LOW_STOCK_THRESHOLD;
		}
	}

	/**
	 * 多店铺下单的处理顺序：恒按店铺 id 升序（与店内按 skuId 升序合起来是全局一致的加锁顺序；与开关无关）。
	 * 只决定处理顺序；返回给前端的订单仍按提交顺序排（由调用方负责）。
	 */
	public <V> List<Map.Entry<String, V>> lockOrder(List<Map.Entry<String, V>> shops) {
		if (shops.size() < 2) {
			return shops;
		}
		return shops.stream()
				.sorted(Map.Entry.<String, V>comparingByKey(Comparator.nullsLast(Comparator.naturalOrder())))
				.toList();
	}

	/**
	 * 逐项扣库存。必须在下单事务里调用（{@code SET LOCAL} 只在事务内有效），mapper 用调用方的（同一事务连接）。
	 *
	 * @throws RuntimeException 「请重新提交」：某项库存不足或 SKU 行不存在（与改动前同一文案），调用方整单回滚
	 * @throws StockBusyException 开关开着、抢不到行锁（等锁超时或死锁）；扣减顺序恒为 skuId 升序（与开关无关）
	 */
	public void decrease(GoodsSkuMapper mapper, List<OrderItem> items) {
		int lowStockThreshold = lowStockThreshold();
		List<OrderItem> lockOrder = items.size() < 2 ? items : items.stream().sorted(LOCK_ORDER).toList();
		if (!enabled()) {
			evictStockCacheAfterCommit(decreaseEach(mapper, lockOrder, lowStockThreshold));
			return;
		}
		long timeoutMs = lockTimeoutMs();
		mapper.setLocalLockTimeout(timeoutMs + "ms");
		Set<String> lowStockSpuIds;
		try {
			lowStockSpuIds = decreaseEach(mapper, lockOrder, lowStockThreshold);
		}
		catch (DataAccessException e) {
			String reason = lockFailureReason(e);
			if (reason == null) {
				throw e;
			}
			// 事务已 aborted（PG 报错后本事务内不能再发语句），不复位 lock_timeout：回滚时 SET LOCAL 一并撤销
			Metrics.counter(BUSY_METRIC, "reason", reason).increment();
			log.warn("下单扣库存抢不到商品行锁，整单回滚 reason={} limitMs={} skus={} cause={}", reason, timeoutMs,
					lockOrder.stream().map(OrderItem::getSkuId).toList(), e.getMostSpecificCause().getMessage());
			throw new StockBusyException(e);
		}
		mapper.resetLocalLockTimeout();
		evictStockCacheAfterCommit(lowStockSpuIds);
	}

	/**
	 * 抢行锁失败的类别；不是锁类失败返回 null（照原样抛）。先认 Spring 的翻译结果（MyBatis-Spring 按 sql-error-codes 把 55P03 翻成
	 * {@link CannotAcquireLockException}、40P01 翻成 {@link DeadlockLoserDataAccessException}，真 PG 实测见 OrderStockDeductionPgTest），
	 * 翻译不出来（如 UncategorizedSQLException）时再按 SQLState 认，不让「翻译器换了」把热点拒单变回 500。
	 */
	static String lockFailureReason(DataAccessException e) {
		String sqlState = e.getMostSpecificCause() instanceof SQLException sql ? sql.getSQLState() : null;
		if (e instanceof CannotAcquireLockException || SQLSTATE_LOCK_NOT_AVAILABLE.equals(sqlState)) {
			return "lock_timeout";
		}
		if (e instanceof DeadlockLoserDataAccessException || SQLSTATE_DEADLOCK_DETECTED.equals(sqlState)) {
			return "deadlock";
		}
		return e instanceof PessimisticLockingFailureException ? "other" : null;
	}

	/** @return 扣后剩余 ≤ 阈值的商品 spuId（阈值为负时恒空） */
	private static Set<String> decreaseEach(GoodsSkuMapper mapper, List<OrderItem> items, int lowStockThreshold) {
		Set<String> lowStockSpuIds = new LinkedHashSet<>();
		items.forEach(orderItem -> {
			Integer remaining = mapper.decreaseStock(orderItem.getSkuId(), orderItem.getQuantity());//更新库存
			if (remaining == null) {
				throw new RuntimeException("请重新提交");
			}
			if (lowStockThreshold >= 0 && remaining <= lowStockThreshold && orderItem.getSpuId() != null) {
				lowStockSpuIds.add(orderItem.getSpuId());
			}
		});
		return lowStockSpuIds;
	}

	/**
	 * 提交后清这些商品的详情库存缓存（{@code mall:goods_stock#5}）。缓存在登记时按当前租户解析：下单线程的租户
	 * 就是订单数据的租户，也是用户端读详情时的租户。登记失败只记 warn，提交后清除失败由
	 * {@link AfterCommitCacheEviction} 只记 error，都不影响下单结果。
	 */
	private void evictStockCacheAfterCommit(Set<String> spuIds) {
		if (cacheManager == null || spuIds.isEmpty()) {
			return;
		}
		for (String spuId : spuIds) {
			try {
				AfterCommitCacheEviction.evict(cacheManager, CacheConstants.MALL_GOODS_STOCK_CACHE, spuId);
			}
			catch (RuntimeException e) {
				log.warn("登记提交后清商品库存缓存失败（详情库存最多晚 5 秒显示）spuId={}: {}", spuId, e.toString());
			}
		}
	}

}
