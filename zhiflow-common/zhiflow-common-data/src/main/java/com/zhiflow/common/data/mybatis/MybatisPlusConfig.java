package com.zhiflow.common.data.mybatis;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.parser.JsqlParserGlobal;
import com.baomidou.mybatisplus.extension.parser.cache.JdkSerialCaffeineJsqlParseCache;
import com.zhiflow.common.data.shardingdatabase.config.ShardingDataBaseProperties;
import com.zhiflow.common.data.shardingtable.config.ShardingTableProperties;
import com.zhiflow.common.data.shardingtable.handler.ShardingTableHandler;
import com.zhiflow.common.data.tenant.BaseTenantHandler;
import com.zhiflow.common.data.tenant.TenantConfigProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

/**
 * MybatisPlus配置
 * https://baomidou.com/guide/page.html
 * @author
 */
@Configuration
@ConditionalOnBean(DataSource.class)
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
@EnableConfigurationProperties({
	TenantConfigProperties.class,
	ShardingTableProperties.class,
})
public class MybatisPlusConfig {

	@Autowired(required = false)
	private ShardingDataBaseProperties shardingDataBaseProperties;

	@Autowired(required = false)
	private ShardingTableProperties shardingTableProperties;

	@Autowired
	private ObjectProvider<MeterRegistry> meterRegistry;

	/**
	 * 创建租户维护处理器对象
	 *
	 * @return 处理后的租户维护处理器
	 */
	@Bean
	@ConditionalOnMissingBean
	public BaseTenantHandler baseTenantHandler() {

		return new BaseTenantHandler();
	}

	/**
	 * 动态表名处理器
	 * 根据当前租户ID自动拼接表名
	 */
	@Bean
	@ConditionalOnMissingBean
	public ShardingTableHandler shardingTableHandler() {
		return new ShardingTableHandler();
	}

	/**
	 * 字段自动填充处理器
	 *
	 * @return MetaObjectHandler
	 */
	@Bean
	@ConditionalOnMissingBean
	public MetaObjectHandler metaObjectHandler() {
		return new ZhiflowMetaObjectHandler();
	}

	/**
	 * PostgreSQL Schema 拦截器
	 *
	 * @return SchemaInterceptor
	 */
	@Bean
	@ConditionalOnMissingBean
	public SchemaInterceptor schemaInterceptor() {
		return new SchemaInterceptor(shardingDataBaseProperties, meterRegistry);
	}

	/**
	 * SQL 改写类拦截器（A1 租户改写缓存 / A2 动态表名快速跳过，恒开）的配置刷新通知：配置变了时作废两者的缓存。
	 */
	@Bean
	public SqlRewriteConfigRefresh sqlRewriteConfigRefresh() {
		return new SqlRewriteConfigRefresh();
	}

	/**
	 * A1 / A2 的命中指标。以 MeterBinder 交给 Boot 绑定，不在建拦截器时急着取 MeterRegistry。
	 */
	@Bean
	public MeterBinder sqlRewritePerfMetrics(MybatisPlusInterceptor mybatisPlusInterceptor) {
		return registry -> mybatisPlusInterceptor.getInterceptors().forEach(inner -> {
			if (inner instanceof MeterBinder binder) {
				binder.bindTo(registry);
			}
		});
	}

	/**
	 * mybatis plus 拦截器各组件配置
	 * @return
	 */
	@Bean
	public MybatisPlusInterceptor mybatisPlusInterceptor() {
		// perf 2026-09-15：开启 JSqlParser 解析缓存。线程采样实测（mall-admin 用户列表页，8 次 SIGQUIT 转储）
		// 64 个 http 线程样本里 54 个卡在 CCJSqlParserUtil.parseStatement——租户拦截器 + 分页拦截器
		// 每次执行都把 SQL 文本重新解析一遍，大 SQL（多子查询）单次解析几十 ms，是后台列表页
		// ~100ms 固定开销的来源；SQL 数减半而耗时不降也是因为它。MP 3.5.x 需显式装配缓存，
		// 命中后按 JDK 序列化深拷贝 Statement，远快于重新解析。key 是 SQL 文本，参数化 SQL 复用率高。
		JsqlParserGlobal.setJsqlParseCache(new JdkSerialCaffeineJsqlParseCache(
				cache -> cache.maximumSize(4096).expireAfterAccess(30, java.util.concurrent.TimeUnit.MINUTES)));
		MybatisPlusInterceptor mybatisPlusInterceptor = new MybatisPlusInterceptor();

		// PostgreSQL Schema 拦截器（仅当配置存在且启用时添加）
		SchemaInterceptor schemaInterceptor = schemaInterceptor();
		mybatisPlusInterceptor.addInnerInterceptor(schemaInterceptor);

		// 多租户配置。perf 2026-10-18 A1：子类缓存改写结果（2026-10-23 删开关，恒开）
		TenantLineRewriteCachingInterceptor tenantLineInnerInterceptor =
				new TenantLineRewriteCachingInterceptor(baseTenantHandler(), sqlRewriteConfigRefresh());
		mybatisPlusInterceptor.addInnerInterceptor(tenantLineInnerInterceptor);

		// 动态表名实现分表。perf 2026-10-18 A2：子类对不可能命中分表的 SQL 跳过分词（2026-10-23 删开关，恒开）
		FastSkipDynamicTableNameInnerInterceptor dynamicTableNameInnerInterceptor = new FastSkipDynamicTableNameInnerInterceptor(
				shardingTableHandler(),
				() -> shardingTableProperties == null ? List.of() : shardingTableProperties.getTables(),
				sqlRewriteConfigRefresh());
		mybatisPlusInterceptor.addInnerInterceptor(dynamicTableNameInnerInterceptor);

		// 乐观锁配置
		mybatisPlusInterceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

		// 分页配置
		// maxLimit 兜底防止 size 传超大值（如 100000）全表拉取拖垮连接池；
		// 全仓已知最大合法 Page size = 1000（ChainRewardGrantTask 批量结算任务），5000 留足 5 倍余量不影响正常业务/导出
		PaginationInnerInterceptor paginationInnerInterceptor = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
		paginationInnerInterceptor.setMaxLimit(5000L);
		mybatisPlusInterceptor.addInnerInterceptor(paginationInnerInterceptor);
		return mybatisPlusInterceptor;
	}
}
