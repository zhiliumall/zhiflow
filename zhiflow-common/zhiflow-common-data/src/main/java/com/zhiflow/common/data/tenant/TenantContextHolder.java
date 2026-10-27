package com.zhiflow.common.data.tenant;

import cn.hutool.core.util.StrUtil;
import com.alibaba.ttl.TransmittableThreadLocal;
import com.alibaba.ttl.TransmittableThreadLocal.Transmitter.Transmittee;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.observability.TraceIds;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

/**
 * @author
 * 租户工具类
 * <p>
 * Schema 隔离模式：仅通过 ThreadLocal 存储租户 ID，SchemaInterceptor 负责设置 search_path
 * <p>
 * 可观测：set / clear 同步 MDC {@code tenant.id}（日志行带租户）与 OTel 当前 span 的 {@code tenant.id} 属性
 * （agent 不在时是空操作）。缺失可以接受，错值不行：因此 clear / pop 一定同步移除。
 * <p>
 * MDC 是普通 ThreadLocal，而 TTL 的回放（replay）与还原（restore）直接改 ThreadLocal、不经过 set / clear。
 * 于是「异步任务里 setTenantId 了却没 clear」（如 auth 登录日志那两个异步任务）时，TTL 会把租户还原掉，
 * MDC 却留在池线程上，下一个落到这个线程的任务就顶着上一个租户号打日志。{@link TenantMdcTransmittee}
 * 让 MDC 跟着 TTL 一起回放、一起还原。
 */
@Slf4j
public final class TenantContextHolder {

	private static final ThreadLocal<String> THREAD_LOCAL_TENANT = new TransmittableThreadLocal<>();

	static {
		TransmittableThreadLocal.Transmitter.registerTransmittee(TenantMdcTransmittee.INSTANCE);
	}

	private TenantContextHolder() {
		throw new IllegalStateException("Utility class");
	}

	/**
	 * TTL 设置租户 ID
	 *
	 * @param tenantId
	 */
	public static void setTenantId(String tenantId) {
		// Schema 隔离模式：仅存储租户 ID 到 TTL
		// SchemaInterceptor 会在 SQL 执行前自动设置 search_path
		THREAD_LOCAL_TENANT.set(tenantId);
		syncObservability(tenantId);
	}

	/**
	 * 获取 TTL 中的租户 ID
	 *
	 * @return 租户 ID，如果未设置则返回 null
	 */
	public static String getTenantId() {
		String tenantId = THREAD_LOCAL_TENANT.get();
		return StrUtil.isBlank(tenantId) ? null : tenantId;
	}

	/**
	 * 清除租户上下文
	 * <p>
	 * Schema 隔离模式：仅清除 ThreadLocal，不需要清理 DynamicDataSource 上下文
	 */
	public static void clear() {
		THREAD_LOCAL_TENANT.remove();
		syncObservability(null);
	}

	/**
	 * 弹出当前租户上下文（栈操作）
	 * <p>
	 * 用于嵌套场景的正确恢复，配合 setTenantId() 的 push 操作
	 * Schema 隔离模式：仅清除 ThreadLocal，不需要弹出 DynamicDataSource 上下文
	 */
	public static void pop() {
		THREAD_LOCAL_TENANT.remove();
		syncObservability(null);
	}

	/**
	 * MDC {@code tenant.id} 随 TTL 传递：提交任务时抓提交方的值，任务开始时写入执行线程、结束时还原执行线程原来的值
	 * （池线程原来是空的就还原成空；调用方线程自己执行任务时还原成调用方的值）。与任务里有没有 clear 无关。
	 * TTL 对每个 Transmittee 的调用都包了 try/catch，这里再吞一层，绝不影响租户上下文本身的传递。
	 */
	static final class TenantMdcTransmittee implements Transmittee<String, String> {

		static final TenantMdcTransmittee INSTANCE = new TenantMdcTransmittee();

		private TenantMdcTransmittee() {
		}

		@Override
		public String capture() {
			return MDC.get(TraceIds.MDC_TENANT_ID);
		}

		@Override
		public String replay(String captured) {
			String backup = MDC.get(TraceIds.MDC_TENANT_ID);
			putOrRemove(captured);
			return backup;
		}

		@Override
		public String clear() {
			return replay(null);
		}

		@Override
		public void restore(String backup) {
			putOrRemove(backup);
		}

		private static void putOrRemove(String value) {
			try {
				if (value == null) {
					MDC.remove(TraceIds.MDC_TENANT_ID);
				}
				else {
					MDC.put(TraceIds.MDC_TENANT_ID, value);
				}
			}
			catch (RuntimeException e) {
				// 观测代码不打断业务
			}
		}

	}

	/** 观测旁路：只写日志上下文与 span 属性，任何异常都吞掉，绝不影响租户上下文本身。 */
	private static void syncObservability(String tenantId) {
		try {
			if (StrUtil.isBlank(tenantId)) {
				MDC.remove(TraceIds.MDC_TENANT_ID);
				return;
			}
			// 公网回调的租户号是任意可填的路径变量：截断，免得一条请求把日志行撑爆
			String value = tenantId.length() > 32 ? tenantId.substring(0, 32) : tenantId;
			MDC.put(TraceIds.MDC_TENANT_ID, value);
			TraceIds.setSpanAttribute(TraceIds.MDC_TENANT_ID, value);
		}
		catch (RuntimeException e) {
			// 观测代码不打断业务
		}
	}
}
