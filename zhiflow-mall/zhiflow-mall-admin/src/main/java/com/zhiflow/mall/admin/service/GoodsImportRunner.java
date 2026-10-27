package com.zhiflow.mall.admin.service;

/**
 * 导入任务的异步执行器。
 *
 * <p>单独成 bean 而不是在 {@link GoodsImportTaskService} 里自调用——自调用不走代理，
 * {@code @Async} 会静默失效，接口会变成同步跑到超时。
 */
public interface GoodsImportRunner {

	/**
	 * 异步执行一个已置为运行态的任务。
	 *
	 * @param taskId   任务ID
	 * @param tenantId 显式传入：执行线程不在请求上下文里，{@code TenantContextHolder} 需要自己恢复，
	 *                 否则 SchemaInterceptor 设不出 search_path，SQL 会打到错误的 schema
	 */
	void run(String taskId, String tenantId);
}
