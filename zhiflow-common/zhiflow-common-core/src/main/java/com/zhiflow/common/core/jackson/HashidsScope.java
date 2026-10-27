package com.zhiflow.common.core.jackson;

/**
 * {@code @HashidsId} 的<b>生效范围</b>开关。
 *
 * <h2>为什么需要它（这是单体特有的问题）</h2>
 * <p>{@code hashids.salt} 是 <b>mall-api 的前后端契约</b>——C 端拿到的 id 一律是 hashid 形态，
 * 回传时由 {@code HashidsDeserializer} 解码。它<b>只写在 {@code zhiflow-mall-api-dev.yml}</b>。
 *
 * <p>多进程形态下这个范围是<b>由进程边界天然保证</b>的：只有 mall-api 那个进程加载了那份配置，
 * 其余服务（mall-admin / upms / im / pay / live / weixin）的 {@link IdCodec} 是 noOp，
 * id 原样进出。它们的代码也正是按「id 是原始雪花」写的——mall-admin 生产代码里
 * {@code @HashidsId} 的真实使用是 <b>0 处</b>，{@code @PathVariable}/{@code @RequestParam}
 * 不走 Jackson，拿到 hashid 也不会解码。
 *
 * <p>单体把 8 个服务的配置并进同一个进程后，<b>salt 变成了进程级的</b>，
 * 于是 mall-admin 的响应体也开始吐 hashid。2026-08-22 实测两处后果：
 * <ul>
 * <li>搜索索引主键被编码（{@code 1785393165689417} → {@code dqrddyvgz6m2fmmxdj48}），
 *     读侧按原始 id 过滤恒零命中 ⇒ H5 装修「暂无商品」；</li>
 * <li>后台 {@code /mall/goodsspu/page} 返回 {@code gje3m6yr7weqfpp8v62q}，
 *     拿它回查 {@code /mall/goodsspu/{id}} 得到「商品不存在」⇒
 *     <b>列表能出、点进去查不到</b>。</li>
 * </ul>
 *
 * <h2>为什么默认是「开」而不是「关」</h2>
 * <p>⚠️ 默认必须等于<b>现状</b>：「配了 salt 就编码」。
 * 反过来把默认设成关，多进程的 mall-api 会当场停止编码 —— 那是生产 C 端的契约，
 * H5 已经在用 hashid 形态的 id 请求商详。
 * ⇒ 多进程形态<b>一行新逻辑都不执行</b>（没人调 {@link #setDefaultEnabled}、没人调 {@link #set}），
 * 「零变化」由此保证，而不是靠逻辑等价论证。这与 {@code PathPrefixProperties#namespaces}
 * 留空即恒等变换是同一条纪律。
 *
 * <p>单体在启动时把进程级默认置为<b>关</b>（对齐「大多数服务没有 salt」），
 * 再由 {@code HashidsScopeFilter} 只对 mall-api 命名空间的请求<b>逐请求打开</b>。
 * 非请求线程（定时任务、MQ 消费、CDC sink）落在进程级默认上 = 关 = 与多进程一致。
 *
 * <h2>⚠️ 依赖的一个前提：带 {@code @HashidsId} 的对象不进 Redis 缓存</h2>
 * <p>逐请求切换意味着「写入线程」与「读取线程」的作用域<b>可能不同</b>。
 * 一条缓存记录若在请求线程上以 hashid 形态写入、之后在后台线程上读出，
 * 反序列化侧不解码 ⇒ 拿到的是 hashid 字符串，再拿去查库就恒空。
 *
 * <p>2026-08-22 核过：mall-api 里 {@code GoodsSpu} 的 {@code @Cacheable} 是<b>注释掉</b>的
 * （原注释写着「不能加缓存」），{@code GoodsSku} / {@code PlaceOrderDTO} 也没有缓存点 ⇒
 * 这条路径当前不存在。<b>给它们任何一个加缓存之前，先回来看这一段。</b>
 *
 * @see IdCodecHolder
 */
public final class HashidsScope {

	/**
	 * 进程级默认。{@code true} = 现状（配了 salt 就编码）。
	 * <b>只有单体会把它置为 false</b>；多进程形态没有任何调用方。
	 */
	private static volatile boolean defaultEnabled = true;

	/**
	 * 逐请求覆盖。{@code null} = 没有覆盖，落到 {@link #defaultEnabled}。
	 * <p>用 {@code ThreadLocal} 而不是 {@code InheritableThreadLocal}：序列化发生在
	 * MVC 的请求线程上，子线程不该继承——继承反而会让异步任务莫名其妙地开始编码。
	 */
	private static final ThreadLocal<Boolean> OVERRIDE = new ThreadLocal<>();

	private HashidsScope() {
	}

	/** 当前线程上 {@code @HashidsId} 是否应当编码/解码。 */
	public static boolean isEnabled() {
		Boolean override = OVERRIDE.get();
		return override != null ? override : defaultEnabled;
	}

	/** 设进程级默认。只有单体装配处调用。 */
	public static void setDefaultEnabled(boolean enabled) {
		defaultEnabled = enabled;
	}

	/** 读进程级默认（供装配自检与测试）。 */
	public static boolean isDefaultEnabled() {
		return defaultEnabled;
	}

	/** 为当前线程设覆盖值。调用方<b>必须</b>在 finally 里 {@link #clear()}，否则会污染线程池里的下一个请求。 */
	public static void set(boolean enabled) {
		OVERRIDE.set(enabled);
	}

	/** 清除当前线程的覆盖值。 */
	public static void clear() {
		OVERRIDE.remove();
	}

}
