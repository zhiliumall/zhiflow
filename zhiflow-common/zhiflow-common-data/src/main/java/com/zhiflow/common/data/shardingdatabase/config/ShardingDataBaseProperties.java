/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.data.shardingdatabase.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 分库配置（Schema 隔离模式）
 * <p>
 * 用于配置租户 Schema 隔离模式，支持多租户数据隔离。
 * 工作原理：通过 SchemaInterceptor 在 SQL 执行前设置 search_path，
 * 自动路由到对应的租户 Schema（如：zhiflow_mall_1, zhiflow_mall_2）。
 * <p>
 * 配置说明：
 * - enabled: 是否启用 Schema 隔离（默认 true，启用）
 * - dbname: Schema 前缀（默认 zhiflow_mall）
 * - 租户 Schema 命名规则：{dbname}_{tenantId}
 *
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "spring.datasource.sharding")
public class ShardingDataBaseProperties {

	/**
     * 是否启用 Schema 隔离模式
     * <p>
     * 默认值：true（启用）
     * <p>
     * 启用后，SchemaInterceptor 会根据租户 ID 动态设置 search_path，
     * 实现租户数据隔离。
     * <p>
     * 示例：
     * - tenantId = "1" → search_path = zhiflow_mall_1
     * - tenantId = "2" → search_path = zhiflow_mall_2
	 */
	private Boolean enabled;
	/**
	 * 数据库 Schema 前缀名
	 * <p>
	 * 用于构建租户 Schema 名称，各租户 Schema 命名规则：dbname_tenantId
	 * <p>
	 * 示例：
	 * - dbname = "zhiflow_mall"
	 * - tenantId = "1" → Schema = "zhiflow_mall_1"
	 * - tenantId = "123" → Schema = "zhiflow_mall_123"
	 * <p>
	 * 默认值：zhiflow_mall
	 */
	private String dbname;

	/**
	 * 与租户 schema 一起放进 search_path 的共享 schema（单数据源后 upms / wx 与商城共用一个连接）。
	 * 顺序即解析优先级，排在租户 schema 之后、public 之前；只允许小写字母、数字、下划线。
	 */
	private java.util.List<String> sharedSchemas = new java.util.ArrayList<>();

	/** 只允许形如 {@code zhiflow_upms} 的名字进 search_path（它会被直接拼进 SET 语句）。 */
	public void setSharedSchemas(java.util.List<String> sharedSchemas) {
		java.util.List<String> checked = new java.util.ArrayList<>();
		for (String s : sharedSchemas == null ? java.util.List.<String>of() : sharedSchemas) {
			if (s == null || !s.matches("[a-z_][a-z0-9_]*")) {
				throw new IllegalArgumentException("spring.datasource.sharding.shared-schemas 里有非法 schema 名：" + s);
			}
			checked.add(s);
		}
		this.sharedSchemas = checked;
	}

	/**
	 * 数据库用户名
	 * <p>
	 * 用于连接 PostgreSQL 数据库，需要有创建和访问 Schema 的权限。
	 * <p>
	 * 推荐配置：
	 * - 使用专用数据库账号
	 * - 授予 CREATE 权限在指定 database 上
	 * - 授予所有 tenant schema 的 USAGE 和 SELECT/INSERT/UPDATE/DELETE 权限
	 */
	private String dbusername;
	/**
	 * 数据库密码
	 * <p>
	 * 建议使用加密存储，配合 Jasypt 加密组件。
	 */
	private String dbpassword;

	/**
	 * 数据库连接 URL
	 * <p>
	 * 用于连接 PostgreSQL 数据库，注意不要包含 currentSchema 参数。
	 * Schema 隔离模式下，search_path 由 SchemaInterceptor 动态设置。
	 * <p>
	 * 示例：
	 * jdbc:postgresql://host:port/zhiflow?ssl=false&reWriteBatchedInserts=true
	 */
	private String url;

	/**
	 * 超管（系统租户）默认指向的租户 ID
	 * <p>
	 * 当 BaseUser.tenantId = SYSTEM_TENANT_ID（"-1"）时，SchemaInterceptor 会把
	 * search_path 指向 {dbname}_{defaultTenantId}（默认 zhiflow_mall_1），
	 * 而不是 base schema（base schema 数据状态不明，不可作为业务读写目标）。
	 * <p>
	 * 配置项：mall.default-tenant-id，默认值 "1"。
	 * 走独立 prefix（而非 spring.datasource.sharding）是为了在多个业务模块里复用同一个语义。
	 */
	@Value("${mall.default-tenant-id:1}")
	private String defaultTenantId = "1";

	/**
	 * SchemaInterceptor 按连接记忆 search_path 的开关（事务内复用 2026-10-18 A3，10-23 起不再单独设开关）。
	 * <p>
	 * true：语句命中"该物理连接上次在 autoCommit 下已 SET 同一 search_path"的记忆时跳过重复 SET，省一次 DB 往返。
	 * autoCommit 与事务内都复用：记忆只来自 autoCommit 下已提交的 SET，事务 / savepoint 回滚最多撤回到这个已提交值，
	 * 所以命中即正确；事务内一旦需要 SET（目标不同），照旧 SET 并删掉记忆，本事务剩余语句全部照旧 SET。
	 * <p>
	 * false（默认）：每条 SQL 前都 SET（autoCommit 与事务内都一样），与历史行为逐字节一致。也是一键回退手段。
	 * <p>
	 * 前提见 SchemaInterceptor#searchPathMemo 的不变式；部署约束：业务连接池不能经过
	 * {@code pool_mode = transaction} 的 pgbouncer（后端连接会在语句之间换掉，记忆不再对应真实会话）。
	 * <p>
	 * 配置项：zhiflow.schema-interceptor.memoize。本类是 @RefreshScope，
	 * 配置改完即时生效（无需重启），一行配置可回退。
	 */
	@Value("${zhiflow.schema-interceptor.memoize:false}")
	private Boolean schemaInterceptorMemoize = Boolean.FALSE;

	/**
	 * 租户分库连接上「没有租户号」的 SQL 怎么处理（2026-09-27 数据库方案第 6 步第 4 块）。
	 * <p>
	 * 没有租户号时 SchemaInterceptor 不设 search_path，这条 SQL 会跑在这条池化连接<b>上一次被设成的 schema</b>
	 * （可能是别的租户）或库默认的 base schema 上。先记录、清零后再改为报错：
	 * <ul>
	 *   <li>{@code off}（默认）：与历史行为逐字节一致，直接放行、不记录；</li>
	 *   <li>{@code log}：照常放行，按 MyBatis 语句 id 计数（{@code zhiflow_schema_no_tenant_total}），
	 *       每个语句 id 首次出现时打一条带调用方的 WARN；不在连接上多执行任何语句；</li>
	 *   <li>{@code reject}：留给第三阶段（记录清零、允许名单就绪后）实现，目前与 {@code log} 相同，只记录不拦。</li>
	 * </ul>
	 * 配置项：zhiflow.schema-interceptor.no-tenant-mode。本类是 @RefreshScope，配置改完即时生效，一行配置可回退。
	 */
	@Value("${zhiflow.schema-interceptor.no-tenant-mode:off}")
	private String noTenantMode = "off";
}
