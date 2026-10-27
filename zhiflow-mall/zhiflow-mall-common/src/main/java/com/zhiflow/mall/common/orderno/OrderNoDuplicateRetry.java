package com.zhiflow.mall.common.orderno;

import com.zhiflow.common.core.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import java.util.concurrent.Callable;

/**
 * `order_info_order_no_idx` 冲突的有限重试。
 *
 * <h2>⚠️ 必须在事务边界之外调用</h2>
 *
 * PostgreSQL 一旦报错，整个事务进入 aborted 状态，后续任何语句都返回
 * {@code current transaction is aborted}。因此**在原事务内部捕获 DuplicateKeyException
 * 再重试是无效的**——重试的那条 INSERT 一样会失败，只是错误信息变得更难懂。
 * 本仓已有三处为此把动作挪到 {@code afterCommit} 的记载
 * （{@code CartEventFlowListener:25}、mall-admin {@code OrderInfoServiceImpl:537}、
 * {@code GoodsAppraisesServiceImpl:93}）。
 *
 * <p>所以本类的 {@code action} 必须是一个**完整的事务单元**（例如一次
 * {@code orderInfoService.orderAddOne(...)} 调用），由本类在其外部重跑。
 *
 * <h2>只认一个约束名</h2>
 *
 * 只有根因消息里含 {@code order_info_order_no_idx} 才重试。其他约束冲突
 * （订单项、幂等键、业务唯一键等）一律**原样抛出**——那些不是重号，重试解决不了，
 * 吞掉只会把真问题变成静默失败。
 *
 * <p>重试**恰好一次**：撞号是同毫秒同序列的巧合，换个号即可；连撞两次说明不是巧合
 * （多半是 T2 要解决的机器位重复），继续重试没有意义，应当让它失败并告警。
 */
public class OrderNoDuplicateRetry {

	private static final Logger log = LoggerFactory.getLogger(OrderNoDuplicateRetry.class);

	/** 活跃行的 order_no 唯一索引名（S8 V80 产物，带 WHERE del_flag='0'）。 */
	static final String ORDER_NO_CONSTRAINT = "order_info_order_no_idx";

	private final OrderNoMetrics metrics;

	private final OrderNoFailureSink failureSink;

	public OrderNoDuplicateRetry(OrderNoMetrics metrics, OrderNoFailureSink failureSink) {
		this.metrics = metrics;
		this.failureSink = failureSink;
	}

	/**
	 * 执行一个完整事务单元，撞 order_no 唯一索引时重跑一次。
	 *
	 * @param action 必须自带事务边界，且可安全重跑（重跑会重新生成订单号）
	 * @throws BizException 重试后仍冲突
	 */
	public <T> T execute(Callable<T> action) throws Exception {
		try {
			return action.call();
		}
		catch (DuplicateKeyException first) {
			if (!isOrderNoConflict(first)) {
				// 不是重号：原样抛出，不计数、不吞
				throw first;
			}
			log.warn("订单号撞上 {}，重试一次", ORDER_NO_CONSTRAINT, first);
			try {
				T result = action.call();
				metrics.dupRetry(OrderNoMetrics.RESULT_RECOVERED);
				return result;
			}
			catch (DuplicateKeyException second) {
				metrics.dupRetry(OrderNoMetrics.RESULT_EXHAUSTED);
				metrics.generateFail(OrderNoMetrics.REASON_DUPLICATE);
				log.error("订单号连续两次撞上 {}，放弃重试——多半不是巧合，检查机器位是否重复（T2）",
						ORDER_NO_CONSTRAINT, second);
				failureSink.record(OrderNoMetrics.REASON_DUPLICATE,
						"连续两次撞 " + ORDER_NO_CONSTRAINT, second);
				throw new BizException("订单创建失败，请重试");
			}
		}
	}

	/**
	 * 只按约束名判定。PG 的重复键报错形如
	 * {@code duplicate key value violates unique constraint "order_info_order_no_idx"}，
	 * 该文本在 {@code DuplicateKeyException} 的 message 或其 cause 链里。
	 */
	static boolean isOrderNoConflict(DuplicateKeyException e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			String message = t.getMessage();
			if (message != null && message.contains(ORDER_NO_CONSTRAINT)) {
				return true;
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}
}
