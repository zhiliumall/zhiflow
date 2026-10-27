package com.zhiflow.common.data.mybatis;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.jsqlparser.enums.ExpressionAppendMode;
import com.zhiflow.common.data.tenant.BaseTenantHandler;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ClassUtils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 租户条件改写结果缓存（2026-10-18 下单吞吐 A1；2026-10-23 删开关，恒开）。
 *
 * <p><b>为什么</b>：600 单/秒剖析里 JSqlParser 解析缓存命中后的 JDK 反序列化深拷贝约占 mall-api CPU 23%
 * （{@code JdkSerialCaffeineJsqlParseCache} 存的是字节数组，每次命中都重建整棵 AST），再加上 AST 遍历和
 * {@code Statement.toString()}。同一条 SQL 在同一租户下改写结果恒定，直接缓存「改写后的 SQL 字符串」，
 * 命中时一次 map 查找代替「反序列化 + 遍历 + toString」。
 *
 * <p><b>🔴 这是租户隔离边界</b>：key 漏掉改写结果的任何一个输入，就会把 A 租户的 SQL 发给 B 租户。
 * 逐行核对 MP 3.5.16 {@code JsqlParserSupport.parserSingle/parserMulti → processParser →
 * TenantLineInnerInterceptor.processInsert/Update/Delete/Select → BaseMultiTableInnerInterceptor}，
 * 改写结果只依赖：
 * <ol>
 *   <li>原 SQL 文本、走 single 还是 multi（multi 会按 {@code ;} 拆开再拼）—— 进 key；</li>
 *   <li>{@code obj}（whereSegment）—— 租户拦截器两处调用都传 null；非 null 时不走缓存；</li>
 *   <li>处理器：{@code getTenantId()} / {@code ignoreTable()} 只依赖 {@link TenantContextHolder#getTenantId()}
 *       （已规范化，空白即 null）—— 进 key；{@code getTenantIdColumn()}、{@code ignoreTable()} 依赖
 *       {@code base.tenant.column/tables}；{@code ignoreInsert()} 用接口默认实现，只依赖 SQL 列名与租户列名
 *       —— 配置刷新时 {@link #invalidateAll()} 换代次；</li>
 *   <li>{@code expressionAppendMode} 与处理器实例本身 —— setter 里换代次；</li>
 *   <li>JSqlParser 的解析 / 打印 —— 对同一文本是确定性的。</li>
 * </ol>
 * 只认 {@link BaseTenantHandler} 本身：换成别的处理器（子类也算）就不缓存，因为上面的推导对它不成立（启动时 WARN）。
 *
 * <p><b>代次</b>：key 带着取值时的代次。作废时先换代次再清空：并发里拿旧代次算完、作废后才放进来的条目，
 * 再也不会被新代次的 key 查到，只是等着被淘汰。拿到新代次的线程，一定是在配置换新之后才开始算的。
 *
 * <p>不缓存的情形（{@code obj} 非 null、SQL 为 null、处理器不是 {@link BaseTenantHandler}）直接调父类，与官方逐字节相同。
 * 命中时跳过了父类的 debug 日志（original SQL / parse the finished SQL）。
 */
@Slf4j
public class TenantLineRewriteCachingInterceptor extends TenantLineInnerInterceptor implements MeterBinder {

	/** Micrometer 缓存名（{@code cache.gets{cache=...,result=hit|miss}} 等）。 */
	static final String CACHE_METRIC_NAME = "mybatis-tenant-sql-rewrite";

	/**
	 * 权重上限（权重 = 原 SQL + 改写后 SQL 的字符数）。约 1600 万字符：拉丁字符紧凑存储下约 16 MB，最坏 32 MB。
	 * 不按条数限：批量 INSERT 的 SQL 动辄几十 KB，按条数限的话少数大 SQL 就能把内存撑大。
	 */
	static final long MAX_WEIGHT_CHARS = 16_000_000L;

	private final Cache<RewriteKey, String> cache = Caffeine.newBuilder()
		.maximumWeight(MAX_WEIGHT_CHARS)
		.weigher((RewriteKey key, String value) -> key.sql().length() + value.length())
		.expireAfterAccess(30, TimeUnit.MINUTES)
		.recordStats()
		.build();

	private final AtomicLong generation = new AtomicLong();

	/** 处理器是不是 {@link BaseTenantHandler} 本身（缓存的前提，见类注释）。 */
	private volatile boolean cacheableHandler;

	/**
	 * @param tenantLineHandler 租户处理器
	 * @param configRefresh 配置刷新通知；构造时把 {@link #invalidateAll()} 登记进去
	 */
	public TenantLineRewriteCachingInterceptor(TenantLineHandler tenantLineHandler, SqlRewriteConfigRefresh configRefresh) {
		setTenantLineHandler(tenantLineHandler);
		configRefresh.addInvalidationListener(this::invalidateAll);
	}

	/** 缓存 key：改写结果的全部「每条 SQL 不同」的输入，其余输入由代次覆盖。 */
	record RewriteKey(boolean multi, String sql, String tenantId, long generation) {
	}

	@Override
	public String parserSingle(String sql, Object obj) {
		return rewrite(false, sql, obj);
	}

	@Override
	public String parserMulti(String sql, Object obj) {
		return rewrite(true, sql, obj);
	}

	private String rewrite(boolean multi, String sql, Object obj) {
		if (obj != null || sql == null || !this.cacheableHandler) {
			return multi ? super.parserMulti(sql, obj) : super.parserSingle(sql, obj);
		}
		// 先取代次再算：见类注释「代次」
		RewriteKey key = new RewriteKey(multi, sql, TenantContextHolder.getTenantId(), this.generation.get());
		String cached = this.cache.getIfPresent(key);
		if (cached != null) {
			return cached;
		}
		// 不用 cache.get(key, loader)：解析可能几十 ms，不在 Caffeine 的计算锁里做。解析失败照常抛，不缓存
		String rewritten = multi ? super.parserMulti(sql, null) : super.parserSingle(sql, null);
		if (rewritten != null) {
			this.cache.put(key, rewritten);
		}
		return rewritten;
	}

	/** 配置变了：换代次并清空。 */
	public void invalidateAll() {
		this.generation.incrementAndGet();
		this.cache.invalidateAll();
	}

	@Override
	public void setTenantLineHandler(TenantLineHandler tenantLineHandler) {
		super.setTenantLineHandler(tenantLineHandler);
		// getUserClass：bean 若被 CGLIB 代理（加了切面），按目标类判断
		this.cacheableHandler = tenantLineHandler != null
				&& ClassUtils.getUserClass(tenantLineHandler) == BaseTenantHandler.class;
		if (tenantLineHandler != null && !this.cacheableHandler) {
			log.warn("租户处理器 {} 不是 BaseTenantHandler，不缓存租户改写结果", tenantLineHandler.getClass().getName());
		}
		invalidateAll();
	}

	@Override
	public void setExpressionAppendMode(ExpressionAppendMode expressionAppendMode) {
		super.setExpressionAppendMode(expressionAppendMode);
		invalidateAll();
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		CaffeineCacheMetrics.monitor(registry, this.cache, CACHE_METRIC_NAME);
	}

	/** 测试用。 */
	long estimatedSize() {
		this.cache.cleanUp();
		return this.cache.estimatedSize();
	}

	/** 测试用。 */
	long hitCount() {
		return this.cache.stats().hitCount();
	}

}
