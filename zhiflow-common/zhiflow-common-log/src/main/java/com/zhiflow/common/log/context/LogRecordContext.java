package com.zhiflow.common.log.context;

import java.util.HashMap;
import java.util.Map;

/**
 * 操作日志模板的变量袋（S4，对齐 yudao/mzt-biz-log 的 {@code LogRecordContext}）。
 *
 * <h2>为什么需要它</h2>
 *
 * 删除类接口的主流形态是 {@code DELETE /{id}}，<b>方法入参只有一个 id</b>。
 * 纯 SpEL 解析 {@code #id} 只能拿到 id，拿不到「被删的东西叫什么」——
 * 而那正是要写进日志的内容。于是让业务代码在删除前把已经加载出来的对象塞进来：
 *
 * <pre>{@code
 * CouponInfo coupon = couponInfoService.getById(id);
 * LogRecordContext.put("coupon", coupon);
 * LogRecordContext.put("impact", entityImpactService.couponInfoImpact(id));
 * couponInfoService.removeById(id);
 * }</pre>
 *
 * 配合注解上的模板：
 *
 * <pre>{@code
 * @SysLog(value = "删除电子券",
 *         content = "'删除优惠券模板「' + #coupon.name + '」，连带作废已发放 ' + #impact.total + ' 张'")
 * }</pre>
 *
 * <h2>⚠️ 清理是强制的</h2>
 *
 * ThreadLocal 不清理，线程复用时会把上一次请求的变量带给下一次——本项目已经在
 * {@code ThirdSessionHolder} 上踩过这个坑（装修 AB 取页矩阵那次，线程复用残留登录态
 * 污染了人群 scope 判定，生产实测）。
 *
 * <p>{@code SysLogAspect} 在 {@code finally} 里无条件调 {@link #clear()}。
 * 业务代码<b>不需要</b>自己清理，但也<b>不要</b>在切面之外长期持有本类的引用。
 */
public final class LogRecordContext {

	private static final ThreadLocal<Map<String, Object>> VARIABLES = new ThreadLocal<>();

	private LogRecordContext() {
	}

	/** 放入一个模板变量；同名覆盖 */
	public static void put(String name, Object value) {
		Map<String, Object> map = VARIABLES.get();
		if (map == null) {
			map = new HashMap<>(8);
			VARIABLES.set(map);
		}
		map.put(name, value);
	}

	/**
	 * 取出当前线程的全部变量。
	 *
	 * @return 永不为 null；没有变量时返回空 Map
	 */
	public static Map<String, Object> getAll() {
		Map<String, Object> map = VARIABLES.get();
		return map == null ? Map.of() : map;
	}

	/** 由 {@code SysLogAspect} 在 finally 中调用，业务代码不必自己调 */
	public static void clear() {
		VARIABLES.remove();
	}
}
