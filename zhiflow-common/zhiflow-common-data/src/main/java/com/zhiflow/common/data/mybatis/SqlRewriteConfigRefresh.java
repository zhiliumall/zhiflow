package com.zhiflow.common.data.mybatis;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SQL 改写类拦截器的「配置变了」通知（2026-10-18 下单吞吐 A1/A2）。
 *
 * <p>A1 租户条件改写结果缓存（{@link TenantLineRewriteCachingInterceptor}）与 A2 动态表名快速跳过
 * （{@link FastSkipDynamicTableNameInnerInterceptor}）恒开（2026-10-23 删开关：「代码默认关」两次让新环境静默变慢）。
 * 这里只剩一件事：配置变了时让两者作废各自按配置算出的缓存。
 */
public class SqlRewriteConfigRefresh {

	private final List<Runnable> invalidationListeners = new CopyOnWriteArrayList<>();

	/** 拦截器构造时登记：配置一变就作废自己的缓存。 */
	public void addInvalidationListener(Runnable listener) {
		this.invalidationListeners.add(listener);
	}

	/**
	 * 让所有登记的缓存作废。开源分支没有配置中心，配置只在启动时读，
	 * 运行中不会触发；留着给测试与以后接入配置热更新时调用。
	 */
	public void onConfigChanged() {
		for (Runnable listener : this.invalidationListeners) {
			listener.run();
		}
	}

}
