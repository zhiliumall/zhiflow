package com.zhiflow.mall.api.trade;

import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.mall.common.constant.MallReturnCode;

/**
 * 热点商品下单忙：扣库存抢不到行锁（等锁超过上限 PG 55P03，或被判死锁 40P01，见 {@link OrderStockDeduction}）整单回滚，
 * 或在拿数据库连接之前就没领到这个 SKU 的在途名额（{@link HotSkuOrderLimiter}，此时还没开事务）。用户稍后重提即可。
 *
 * <p>是业务拒绝而不是系统故障：下单接口接住后回 {@link MallReturnCode#ERR_70008}（HTTP 200），并释放防重 token；
 * 下单打点记 biz_reject。秒杀异步建单同样经过这里，消费者按建单失败处理（回补 Redis 桶、结果键写失败原因）。
 */
public class StockBusyException extends BizException {

	public static final MallReturnCode CODE = MallReturnCode.ERR_70008;

	public StockBusyException(Throwable cause) {
		super(CODE.getMsg());
		initCause(cause);
	}

	/** 没有底层异常：{@link HotSkuOrderLimiter} 在开事务之前就拒了。 */
	public StockBusyException() {
		super(CODE.getMsg());
	}

}
