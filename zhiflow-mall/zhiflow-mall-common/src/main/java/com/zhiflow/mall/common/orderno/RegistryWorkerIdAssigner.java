package com.zhiflow.mall.common.orderno;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 从 {@code public.id_worker_node} 抢占一个机器位槽位。
 *
 * <h2>为什么用原生 JDBC 而不是 MyBatis mapper</h2>
 *
 * 抢占发生在**启动期**，此时没有租户上下文。本仓多租户是「单数据源 + SET search_path」，
 * 无租户上下文时连接落到 DB 默认 schema（实测 {@code zhiflow_mall}，**不是 public**）。
 * 走 mapper 还要穿过租户拦截器，行为更难预期。直接用 {@link DataSource} + 全限定表名
 * {@code public.id_worker_node} 把这两件事一次绕开——{@code MallTenantMigrator} 用的是同一手法。
 *
 * <p>⚠️ 本类里每一条 SQL 都必须写 {@code public.} 前缀。漏一处的后果不是报错，
 * 而是打到租户 schema 的同名表上（租户 schema 在 search_path 里排在 public 前面），
 * 槽位分配就此分裂成每租户一套，**唯一性保证归零且全程无异常**。
 *
 * <h2>抢不到就拒绝启动</h2>
 *
 * 构造器抢不到槽位即抛异常 ⇒ Bean 创建失败 ⇒ 应用启动失败。这是刻意的：
 * 降级成 {@code (0,0)} 会让唯一性退化为「人工配置对不对」，而配错不会有任何报错，
 * 是静默碰撞。宁可起不来，也不要带着重号风险跑。
 *
 * <p>逃生舱是配置项 {@code orderno.worker-id-strategy=fixed}，不是自动降级。
 */
public class RegistryWorkerIdAssigner implements WorkerIdAssigner, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(RegistryWorkerIdAssigner.class);

	/** 槽位总数 = dataCenterId(5 bit) × workerId(5 bit)。 */
	static final int TOTAL_SLOTS = 1024;

	static final int WORKER_ID_RANGE = 32;

	private final DataSource dataSource;

	private final WorkerIdRegistryProperties properties;

	private final String nodeIdentity;

	private final String appName;

	private final long workerId;

	private final long dataCenterId;

	private final int slotId;

	private final ScheduledExecutorService heartbeat;

	public RegistryWorkerIdAssigner(DataSource dataSource, WorkerIdRegistryProperties properties,
			String appName) {
		this.dataSource = dataSource;
		this.properties = properties;
		this.appName = appName;
		this.nodeIdentity = buildNodeIdentity(appName);

		this.slotId = acquireSlotOrFail();
		this.dataCenterId = slotId / WORKER_ID_RANGE;
		this.workerId = slotId % WORKER_ID_RANGE;

		log.info("订单号机器位已抢占：slot={} dataCenterId={} workerId={} identity={}",
				slotId, dataCenterId, workerId, nodeIdentity);

		this.heartbeat = startHeartbeat();
	}

	/**
	 * 节点标识 = 应用名 + 主机地址 + PID。
	 *
	 * <p>带上它是为了让**同一节点重启时优先拿回原槽位**，否则每次发版都换号段，
	 * 且旧槽位要等过期才释放。容器里 hostname 会变而 IP 通常不变，故用 IP 而非 hostname。
	 * PID 参与是为了同机多进程（本地开发起两个服务）不互相抢。
	 */
	private static String buildNodeIdentity(String appName) {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostAddress();
		}
		catch (Exception e) {
			host = "unknown-host";
		}
		return appName + "@" + host + "#" + ProcessHandle.current().pid();
	}

	/**
	 * 确保槽位表存在。
	 *
	 * <h2>⚠️ 为什么这里要建表（2026-08-16 出过事故）</h2>
	 *
	 * 原设计只靠 flyway {@code V102__id_worker_node.sql} 建表，结果 mall-admin **起不来**：
	 * {@code MallTenantMigrator} 挂在 {@code ApplicationReadyEvent} 上，而本类在
	 * **Bean 创建期**就要用这张表——mall-admin 自己既是建表者又是使用者，
	 * 于是「起不来 → 迁移不跑 → 表永远不存在 → 更起不来」的死锁。
	 * 实测：健康检查 40 次全未就绪，部署流水线自动回滚；事后查 V102 在 flyway 历史里 0 条、
	 * 表 0 张，证明应用根本没活到建表那一步。
	 *
	 * <p>所以抢占前先自建表。这同时消掉了原方案里「必须先发 mall-admin 再发 mall-api」
	 * 那条脆弱的发版顺序约束——现在哪个服务先起都能自举。
	 *
	 * <p>V102 仍然保留：它保证迁移历史完整，也让新建租户 schema 的环境与存量环境一致。
	 * 两处 DDL 内容必须一致，改一处要同步另一处。
	 *
	 * <p>并发建表：{@code CREATE TABLE IF NOT EXISTS} 在 PG 并发下仍可能撞
	 * {@code duplicate key value violates unique constraint "pg_type_typname_nsp_index"}，
	 * 那是「另一个进程刚好同时建成了」，正是我们要的结果，忽略即可——真失败会在紧接着的
	 * 抢占语句上暴露。
	 */
	private void ensureTableExists() {
		String ddl = """
				CREATE TABLE IF NOT EXISTS public.id_worker_node (
				    slot_id       integer     NOT NULL,
				    node_identity varchar(200),
				    app_name      varchar(100),
				    host          varchar(100),
				    pid           varchar(50),
				    acquired_at   timestamptz,
				    heartbeat_at  timestamptz,
				    CONSTRAINT id_worker_node_pkey PRIMARY KEY (slot_id)
				)
				""";
		String seed = """
				INSERT INTO public.id_worker_node (slot_id)
				SELECT generate_series(0, %d)
				ON CONFLICT (slot_id) DO NOTHING
				""".formatted(TOTAL_SLOTS - 1);
		String identityIdx = """
				CREATE UNIQUE INDEX IF NOT EXISTS id_worker_node_identity_idx
				    ON public.id_worker_node (node_identity) WHERE node_identity IS NOT NULL
				""";
		try (Connection c = dataSource.getConnection()) {
			// 已存在就不发 DDL（压测后 CARD-01，2026-10-01）：切到应用专用账号后，即便是 IF NOT EXISTS，
			// PG 也先查权限——public 上没有 CREATE 报 permission denied、不是属主建索引报 must be owner，
			// 每次启动在 PG 日志里留两条 ERROR。表和索引都在时只做槽位预填（纯 DML）。
			boolean tableExists = relationExists(c, "public.id_worker_node");
			boolean indexExists = relationExists(c, "public.id_worker_node_identity_idx");
			for (String sql : new String[] { tableExists ? null : ddl, seed, indexExists ? null : identityIdx }) {
				if (sql == null) {
					continue;
				}
				try (PreparedStatement ps = c.prepareStatement(sql)) {
					ps.execute();
				}
				catch (Exception e) {
					// 并发下另一进程可能刚好建完，忽略；真问题会在抢占语句上暴露
					log.debug("初始化 public.id_worker_node 的某步被跳过（多为并发建表）：{}", e.getMessage());
				}
			}
		}
		catch (Exception e) {
			log.warn("初始化 public.id_worker_node 失败，继续尝试抢占（若表确实不存在，下一步会 fail-fast）", e);
		}
	}

	/** 查不出来（非 PG、权限异常等）一律当「不存在」，退回原来的照发 DDL 行为。包级可见供测试驱动。 */
	static boolean relationExists(Connection c, String qualifiedName) {
		try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
			ps.setString(1, qualifiedName);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() && rs.getBoolean(1);
			}
		}
		catch (Exception e) {
			log.debug("查 {} 是否存在失败，按不存在处理：{}", qualifiedName, e.getMessage());
			return false;
		}
	}

	private int acquireSlotOrFail() {
		try {
			ensureTableExists();
			Integer reused = reuseOwnSlot();
			if (reused != null) {
				log.info("拿回本节点原有槽位 slot={}", reused);
				return reused;
			}
			Integer fresh = claimFreeOrExpiredSlot();
			if (fresh != null) {
				return fresh;
			}
			throw new IllegalStateException(
					"public.id_worker_node 的 " + TOTAL_SLOTS + " 个槽位全被占用且均未过期");
		}
		catch (IllegalStateException e) {
			throw failFast(e);
		}
		catch (Exception e) {
			throw failFast(e);
		}
	}

	/**
	 * ⚠️ 刻意不降级为 (0,0)：降级会让唯一性退化成「人工配置对不对」，而配错是静默碰撞。
	 *
	 * <p>本类会先自建表（见 {@link #ensureTableExists()}），所以走到这里通常是**数据库不可达**，
	 * 而不是表没建——「必须先发 mall-admin」那条发版顺序约束已随自举取消。
	 */
	private IllegalStateException failFast(Exception cause) {
		return new IllegalStateException(
				"订单号机器位抢占失败，拒绝启动（identity=" + nodeIdentity + "）。"
						+ "处置：①确认数据库可达（本进程会自建 public.id_worker_node，"
						+ "故通常不是缺表）；②确认该库账号有 public schema 的建表/写入权限；"
						+ "③若需应急放行，配置 orderno.worker-id-strategy=fixed 退回固定机器位（会失去唯一性保证）",
				cause);
	}

	/** 同一 identity 之前占过的槽位，直接续用。 */
	private Integer reuseOwnSlot() throws Exception {
		String sql = """
				UPDATE public.id_worker_node
				   SET heartbeat_at = now(), acquired_at = now(), app_name = ?, host = ?, pid = ?
				 WHERE node_identity = ?
				RETURNING slot_id
				""";
		try (Connection c = dataSource.getConnection();
			 PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, appName);
			ps.setString(2, hostOf(nodeIdentity));
			ps.setString(3, String.valueOf(ProcessHandle.current().pid()));
			ps.setString(4, nodeIdentity);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : null;
			}
		}
	}

	/**
	 * 抢一个空闲或已过期的槽位。
	 *
	 * <p>{@code FOR UPDATE SKIP LOCKED} 是并发正确性的关键：多个进程同时启动时，
	 * 各自跳过已被别人锁住的行，不会两个进程拿到同一个 slot，也不会互相阻塞。
	 */
	private Integer claimFreeOrExpiredSlot() throws Exception {
		String sql = """
				UPDATE public.id_worker_node
				   SET node_identity = ?, app_name = ?, host = ?, pid = ?,
				       acquired_at = now(), heartbeat_at = now()
				 WHERE slot_id = (
				       SELECT slot_id FROM public.id_worker_node
				        WHERE node_identity IS NULL
				           OR heartbeat_at IS NULL
				           OR heartbeat_at < now() - make_interval(secs => ?)
				        ORDER BY slot_id
				        LIMIT 1
				        FOR UPDATE SKIP LOCKED)
				RETURNING slot_id
				""";
		try (Connection c = dataSource.getConnection();
			 PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, nodeIdentity);
			ps.setString(2, appName);
			ps.setString(3, hostOf(nodeIdentity));
			ps.setString(4, String.valueOf(ProcessHandle.current().pid()));
			ps.setDouble(5, properties.getExpireAfter().toSeconds());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : null;
			}
		}
	}

	private static String hostOf(String identity) {
		int at = identity.indexOf('@');
		int hash = identity.lastIndexOf('#');
		return at >= 0 && hash > at ? identity.substring(at + 1, hash) : "unknown-host";
	}

	private ScheduledExecutorService startHeartbeat() {
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "orderno-workerid-heartbeat");
			t.setDaemon(true);
			return t;
		});
		long seconds = Math.max(1, properties.getHeartbeatInterval().toSeconds());
		executor.scheduleWithFixedDelay(this::renew, seconds, seconds, TimeUnit.SECONDS);
		return executor;
	}

	/**
	 * 续约。单次失败**不**抛出、不释放槽位——要连续失到超过 expireAfter 才会被别人抢走，
	 * 一次网络抖动不该把正在服务的进程搞掉。
	 */
	private void renew() {
		String sql = """
				UPDATE public.id_worker_node
				   SET heartbeat_at = now()
				 WHERE slot_id = ? AND node_identity = ?
				""";
		try (Connection c = dataSource.getConnection();
			 PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setInt(1, slotId);
			ps.setString(2, nodeIdentity);
			if (ps.executeUpdate() == 0) {
				// 槽位已被别人抢走（本进程曾长时间失联）。此刻两个进程可能共用同一机器位，
				// 必须让它显眼——但不能自杀，正在处理的请求还要收尾。
				log.error("⚠️ 机器位槽位 {} 已不属于本节点（identity={}），可能已被其他进程抢占。"
						+ "此时存在重号风险，请尽快重启本进程重新抢占", slotId, nodeIdentity);
			}
		}
		catch (Exception e) {
			log.warn("机器位心跳续约失败 slot={}，将在下个周期重试（连续失败超过 {} 才会失去槽位）",
					slotId, properties.getExpireAfter(), e);
		}
	}

	@Override
	public long getWorkerId() {
		return workerId;
	}

	@Override
	public long getDataCenterId() {
		return dataCenterId;
	}

	public int getSlotId() {
		return slotId;
	}

	public String getNodeIdentity() {
		return nodeIdentity;
	}

	/** 优雅下线：停心跳并释放槽位，让它能被立刻复用而不必等过期。 */
	@Override
	public void close() {
		heartbeat.shutdownNow();
		String sql = """
				UPDATE public.id_worker_node
				   SET node_identity = NULL, heartbeat_at = NULL, acquired_at = NULL
				 WHERE slot_id = ? AND node_identity = ?
				""";
		try (Connection c = dataSource.getConnection();
			 PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setInt(1, slotId);
			ps.setString(2, nodeIdentity);
			ps.executeUpdate();
			log.info("已释放订单号机器位槽位 slot={}", slotId);
		}
		catch (Exception e) {
			// 释放失败不影响停机：槽位会在 expireAfter 之后自动可被抢占
			log.warn("释放机器位槽位失败 slot={}，将等待过期自动回收", slotId, e);
		}
	}
}
