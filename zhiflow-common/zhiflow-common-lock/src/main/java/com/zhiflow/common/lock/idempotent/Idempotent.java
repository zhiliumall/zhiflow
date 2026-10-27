package com.zhiflow.common.lock.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 提交幂等。
 *
 * <p>同一个 {@code Idempotency-Key} 头在 TTL 窗口内重复提交时，
 * <b>只执行一次业务，后续请求原样拿到首次的响应</b>。
 *
 * <h2>与 {@link com.zhiflow.common.lock.annotation.DistributedLock} 的区别</h2>
 *
 * 两者都依赖 Redis，但解决的不是同一件事，<b>不要互相替代</b>：
 *
 * <table border="1">
 *   <tr><th></th><th>@DistributedLock</th><th>@Idempotent</th></tr>
 *   <tr><td>目标</td><td>同一资源不被并发修改</td><td>同一次提交不被执行两次</td></tr>
 *   <tr><td>key</td><td>业务参数（如 {@code #id}）</td><td>客户端生成的 Idempotency-Key 头</td></tr>
 *   <tr><td>第二个请求</td><td>等待或快速失败</td><td><b>返回第一次的响应</b></td></tr>
 *   <tr><td>生命周期</td><td>方法执行期间</td><td>TTL 窗口，跨请求</td></tr>
 * </table>
 *
 * <p>典型差异：用户双击提交，两次请求参数完全一样。锁能挡住并发，但第一把锁释放后
 * 第二个请求照样会执行一遍（下两单）。只有幂等能解决。
 *
 * <h2>用法</h2>
 * <pre>{@code
 * @Idempotent
 * @PostMapping("/refund")
 * public R refund(@RequestBody RefundDTO dto) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

	/**
	 * 缺少 {@code Idempotency-Key} 头时是否拒绝请求。
	 *
	 * <p><b>默认跟随全局配置 {@code idempotent.require-key}（默认 false = 放行）。</b>
	 * 原因：前端 axios 拦截器尚未上线，此刻线上所有请求都不带这个头，
	 * 默认强制会让接口一上线就全挂。前端就绪后可在此处逐端点切成 {@link Required#YES}。
	 */
	Required required() default Required.FOLLOW_GLOBAL;

	/**
	 * 幂等窗口秒数；{@code <= 0} 表示跟随全局配置（默认 60）。
	 */
	int ttlSeconds() default 0;

	enum Required {

		/** 跟随全局 {@code idempotent.require-key} */
		FOLLOW_GLOBAL,
		/** 强制要求带 key，缺失即拒绝 */
		YES,
		/** 永不强制，缺 key 一律放行 */
		NO
	}
}
