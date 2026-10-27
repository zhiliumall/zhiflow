package com.zhiflow.mall.common.orderno;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * {@link OrderNoGenerator} 及其配套的装配。
 *
 * <p><b>为什么是自动配置而不是 {@code @Component}</b>：mall-api 与 mall-admin 两个启动类的
 * {@code @ComponentScan} 都**不包含** {@code com.zhiflow.mall.common}
 * （见 {@code ZhiflowMallApiApplication:52-56} 与 {@code ZhiflowMallApplication:45-46}），
 * 在本包挂 {@code @Component} 扫不到，注入点会在启动期直接失败。
 * mall-common 既有的 {@code MallConfigProperties} / {@code RedisConfigProperties} 走的就是
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}，
 * 本类沿用该惯例——改两个启动类的扫描范围会连带影响跨服务同简单名组件的装配顺序，
 * 风险远大于收益。
 *
 * <p>全部 {@link ConditionalOnMissingBean}：
 * <ul>
 *   <li>{@link WorkerIdAssigner} 是留给 T2 的替换位（启动期动态分配 workerId）</li>
 *   <li>{@link OrderNoFailureSink} 由 mall-api / mall-admin 各自桥接 {@code ErrorLogRecorder}</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class OrderNoGeneratorAutoConfiguration {

	private static final Logger log = LoggerFactory.getLogger(OrderNoGeneratorAutoConfiguration.class);

	@Bean
	@ConfigurationProperties(prefix = "orderno")
	@ConditionalOnMissingBean(WorkerIdRegistryProperties.class)
	public WorkerIdRegistryProperties workerIdRegistryProperties() {
		return new WorkerIdRegistryProperties();
	}

	/**
	 * 机器位来源。默认走注册表抢占（{@code public.id_worker_node}），
	 * 抢不到即拒绝启动——降级会让唯一性退化成「人工配置对不对」，而配错是静默碰撞。
	 *
	 * <p>逃生舱：{@code orderno.worker-id-strategy=fixed} 退回固定机器位（默认 {@code (0,0)}，
	 * 即 T2 之前的行为），**不需要回滚 DDL**。
	 *
	 * <p>{@code DataSource} 用 {@link ObjectProvider} 取：mall-common 是库模块，
	 * 不能假定每个引用方都配了数据源；缺数据源时自动落回固定机器位并告警，
	 * 否则会把「本来就不该用注册表的场景」也拖垮。
	 */
	// destroyMethod 留默认的「推断」：本方法返回类型是 WorkerIdAssigner（接口上没有 close），
	// 写死 destroyMethod="close" 会让 Spring 报 Invalid destruction signature，
	// 三个服务**全部起不来**而编译毫无问题。推断模式会对实现了 AutoCloseable 的实例
	// （RegistryWorkerIdAssigner）调 close 释放槽位，对 FixedWorkerIdAssigner 则跳过。
	@Bean
	@ConditionalOnMissingBean(WorkerIdAssigner.class)
	public WorkerIdAssigner workerIdAssigner(WorkerIdRegistryProperties properties,
			ObjectProvider<DataSource> dataSource,
			@Value("${spring.application.name:unknown}") String appName) {
		if (!properties.isRegistryStrategy()) {
			log.warn("订单号机器位走固定值 ({}, {})（orderno.worker-id-strategy=fixed）。"
					+ "⚠️ 多进程共用同一机器位时，同毫秒同序列即碰撞，"
					+ "且撞上软删墓碑行不报错——这是逃生舱，不是常态配置",
					properties.getFixedDataCenterId(), properties.getFixedWorkerId());
			return new FixedWorkerIdAssigner(properties.getFixedWorkerId(),
					properties.getFixedDataCenterId());
		}
		DataSource ds = dataSource.getIfAvailable();
		if (ds == null) {
			log.warn("未配置 DataSource，订单号机器位落回固定值 ({}, {})。若本进程会生成订单号，"
					+ "请改配 orderno.worker-id-strategy 或补上数据源",
					properties.getFixedDataCenterId(), properties.getFixedWorkerId());
			return new FixedWorkerIdAssigner(properties.getFixedWorkerId(),
					properties.getFixedDataCenterId());
		}
		return new RegistryWorkerIdAssigner(ds, properties, appName);
	}

	/**
	 * {@code MeterRegistry} 用 {@link ObjectProvider} 可选取：mall-common 是库模块，
	 * 不能假定每个引用方都装了 actuator。缺失时降级为不计数，不影响发号。
	 */
	@Bean
	@ConditionalOnMissingBean(OrderNoMetrics.class)
	public OrderNoMetrics orderNoMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
		return new OrderNoMetrics(meterRegistry.getIfAvailable());
	}

	@Bean
	@ConditionalOnMissingBean(OrderNoFailureSink.class)
	public OrderNoFailureSink noOpOrderNoFailureSink() {
		return new NoOpOrderNoFailureSink();
	}

	/**
	 * MyBatis-Plus {@code ASSIGN_ID} 主键（{@code order_info.id} 等）的机器位也取注册表槽位。
	 *
	 * <p>不配这个 bean 时 MP 用 {@code DefaultIdentifierGenerator()}，机器位按 MAC + PID 推导：
	 * 容器里 PID 恒为 1，docker 的 MAC 随机（2026-09-16 实测），两个进程拿到同一机器位的概率不为零，
	 * 同毫秒同序列就是主键冲突。mall-api 上双实例后这不再是理论问题。
	 *
	 * <p>数据中心位平移 16：MP 与 Hutool 的雪花 epoch 相同（都是 1288834974657），不平移的话本进程发的
	 * 主键可能与订单号数值相等（不同列，但排障时会误导）。平移是一一映射，跨进程唯一性不受影响。
	 */
	@Bean
	@ConditionalOnMissingBean(IdentifierGenerator.class)
	public IdentifierGenerator registryIdentifierGenerator(WorkerIdAssigner workerIdAssigner) {
		long dataCenterId = (workerIdAssigner.getDataCenterId() + 16) % 32;
		log.info("MyBatis-Plus 主键机器位取自注册表：workerId={} dataCenterId={}（槽位 dataCenterId={} 平移 16）",
				workerIdAssigner.getWorkerId(), dataCenterId, workerIdAssigner.getDataCenterId());
		return new DefaultIdentifierGenerator(workerIdAssigner.getWorkerId(), dataCenterId);
	}

	@Bean
	@ConditionalOnMissingBean(OrderNoGenerator.class)
	public OrderNoGenerator orderNoGenerator(WorkerIdAssigner workerIdAssigner,
			OrderNoMetrics orderNoMetrics, OrderNoFailureSink orderNoFailureSink) {
		return new OrderNoGenerator(workerIdAssigner, orderNoMetrics, orderNoFailureSink);
	}

	@Bean
	@ConditionalOnMissingBean(OrderNoDuplicateRetry.class)
	public OrderNoDuplicateRetry orderNoDuplicateRetry(OrderNoMetrics orderNoMetrics,
			OrderNoFailureSink orderNoFailureSink) {
		return new OrderNoDuplicateRetry(orderNoMetrics, orderNoFailureSink);
	}
}
