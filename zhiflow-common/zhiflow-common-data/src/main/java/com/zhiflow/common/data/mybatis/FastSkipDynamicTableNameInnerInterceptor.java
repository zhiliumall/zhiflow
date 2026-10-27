package com.zhiflow.common.data.mybatis;

import com.baomidou.mybatisplus.extension.plugins.handler.TableNameHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.data.shardingtable.handler.ShardingTableHandler;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * 动态表名快速跳过（2026-10-18 下单吞吐 A2；2026-10-23 删开关，恒开）。
 *
 * <p><b>为什么</b>：父类每条 SQL 都 {@code new TableNameParser(sql)}（整条 SQL 跑一遍分词正则），600 单/秒剖析里约占
 * mall-api CPU 7.6%；而 mall-api 的 SQL 几乎都不碰分表（{@code base.shardingtable.tables} 只列了日志表）。
 *
 * <p><b>为什么跳过是等价的</b>：父类把每个表名 token（SQL 的子串）交给 {@link ShardingTableHandler}，处理器只在
 * 「租户非空、不是系统租户、且 token 与分表名单里某一项完全相等」时改名，否则原样返回；处理器全原样返回时，
 * 父类拼出来的就是原 SQL。所以下列任一成立时结果必然是原 SQL，不必分词：
 * <ul>
 *   <li>租户为空 / 系统租户（{@link CommonConstants#SYSTEM_TENANT_ID}）；</li>
 *   <li>SQL 文本里（不区分大小写，比处理器的精确匹配更宽）找不到任何一个分表名——token 是 SQL 的子串，
 *       token 等于表名 ⇒ SQL 含这个表名。表名只是别的表名 / 列名的一部分时只会「不跳过」，交给父类判断。</li>
 * </ul>
 * 只认 {@link ShardingTableHandler} 本身（子类也不认），换了处理器上面的推导不成立，一律走父类。
 * 父类在退化输入上（空 SQL 抛下标越界、表名 token 紧贴在上一个表名 token 之后会被吞掉）与「原样返回」不同，
 * 真实 SQL 不会出现这两种形态。
 *
 * <p><b>分表名单</b>：按代次缓存成不可变列表，不在每条 SQL 上穿一次 {@code @RefreshScope} 代理。
 * 配置刷新时 {@link #invalidateAll()} 换代次，下一条 SQL 重读；快照带着读之前的代次，读的过程中被作废就不会被当成新快照。
 *
 * <p>不跳过时直接调父类，与官方逐字节相同。跳过时 {@code hook} 照样执行（与父类 finally 语义一致）。
 */
@Slf4j
public class FastSkipDynamicTableNameInnerInterceptor extends DynamicTableNameInnerInterceptor implements MeterBinder {

	static final String METRIC = "zhiflow.mybatis.dyn_table";

	/** 分表名单的来源（生产为 {@code ShardingTableProperties::getTables}，@RefreshScope 代理）。 */
	private final Supplier<List<String>> shardingTables;

	/** 处理器是不是 {@link ShardingTableHandler} 本身（跳过的前提，见类注释）。 */
	private volatile boolean skippableHandler;

	private final AtomicLong generation = new AtomicLong();

	private volatile TablesSnapshot snapshot;

	private final LongAdder skipped = new LongAdder();

	private final LongAdder parsed = new LongAdder();

	private record TablesSnapshot(long generation, List<String> tables) {
	}

	/**
	 * @param tableNameHandler 表名处理器
	 * @param shardingTables 分表名单来源，必须与处理器读的是同一份配置
	 * @param configRefresh 配置刷新通知；构造时把 {@link #invalidateAll()} 登记进去
	 */
	public FastSkipDynamicTableNameInnerInterceptor(TableNameHandler tableNameHandler,
			Supplier<List<String>> shardingTables, SqlRewriteConfigRefresh configRefresh) {
		super(tableNameHandler);
		this.shardingTables = shardingTables;
		setTableNameHandler(tableNameHandler);
		configRefresh.addInvalidationListener(this::invalidateAll);
	}

	@Override
	public String changeTable(String sql) {
		if (!this.skippableHandler || sql == null || !canSkip(sql)) {
			this.parsed.increment();
			return super.changeTable(sql);
		}
		this.skipped.increment();
		Runnable hook = getHook();
		if (hook != null) {
			hook.run();
		}
		return sql;
	}

	@Override
	public void setTableNameHandler(TableNameHandler tableNameHandler) {
		super.setTableNameHandler(tableNameHandler);
		// getUserClass：bean 若被 CGLIB 代理（加了切面），按目标类判断
		this.skippableHandler = tableNameHandler != null
				&& ClassUtils.getUserClass(tableNameHandler) == ShardingTableHandler.class;
		if (tableNameHandler != null && !this.skippableHandler) {
			log.warn("表名处理器 {} 不是 ShardingTableHandler，不跳过分词", tableNameHandler.getClass().getName());
		}
	}

	/** 结果必然是原 SQL 时返回 true（见类注释）。 */
	boolean canSkip(String sql) {
		String tenantId = TenantContextHolder.getTenantId();
		if (tenantId == null || CommonConstants.SYSTEM_TENANT_ID.equals(tenantId)) {
			return true;
		}
		for (String table : tables()) {
			if (containsIgnoreCase(sql, table)) {
				return false;
			}
		}
		return true;
	}

	/** 配置变了：换代次，下一条 SQL 重读分表名单。 */
	public void invalidateAll() {
		this.generation.incrementAndGet();
	}

	private List<String> tables() {
		long current = this.generation.get();
		TablesSnapshot s = this.snapshot;
		if (s != null && s.generation() == current) {
			return s.tables();
		}
		List<String> configured = this.shardingTables == null ? null : this.shardingTables.get();
		List<String> copy = new ArrayList<>();
		if (configured != null) {
			for (String table : configured) {
				// 空名单项：处理器的 contains 不可能命中空 token（token 非空），跳过它不影响等价
				if (table != null && !table.isEmpty()) {
					copy.add(table);
				}
			}
		}
		List<String> loaded = List.copyOf(copy);
		this.snapshot = new TablesSnapshot(current, loaded);
		return loaded;
	}

	/**
	 * {@code text} 是否含 {@code needle}（不区分大小写）。逐位置比，不分配新串。
	 * 用名单里的原串比（不先转小写），首字符预筛先认原字符：{@code regionMatches(true, ...)} 对逐字符相等的位置恒为真，
	 * 所以「精确包含 ⇒ 判为包含」不受 Unicode 大小写映射（转换后长度变化等）影响。
	 */
	static boolean containsIgnoreCase(String text, String needle) {
		int n = needle.length();
		int max = text.length() - n;
		char first = needle.charAt(0);
		char firstUpper = Character.toUpperCase(first);
		char firstLower = Character.toLowerCase(first);
		for (int i = 0; i <= max; i++) {
			char c = text.charAt(i);
			if ((c == first || Character.toUpperCase(c) == firstUpper || Character.toLowerCase(c) == firstLower)
					&& text.regionMatches(true, i, needle, 0, n)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		FunctionCounter.builder(METRIC, this.skipped, LongAdder::sum)
			.tag("result", "skipped")
			.description("动态表名快速跳过：未分词直接原样返回的 SQL 数")
			.register(registry);
		FunctionCounter.builder(METRIC, this.parsed, LongAdder::sum)
			.tag("result", "parsed")
			.description("动态表名快速跳过：仍交给父类分词的 SQL 数")
			.register(registry);
	}

}
