package com.zhiflow.mall.api.payment;

import java.time.Duration;

/**
 * 渠道能力矩阵。
 *
 * <p>目标：状态机与定时任务里不再出现「如果是某渠道就……」，渠道能力变了只改声明或配置
 * （{@code pay.channel.<code>.cap.<字段>}，见 {@link ChannelCapabilityResolver}）。
 *
 * <p>用到的地方：P6 只在 {@link #close} 为真时向渠道关单；补单任务「已关仍可付」的扫描上限取 {@link #maxPayWindow}；
 * 支付结果查询的探查令牌桶取 {@link #queryQps}。
 *
 * @param close            有关单接口
 * @param expire           下单可传过期时间
 * @param query            可主动查单
 * @param maxPayWindow     关单后渠道仍可能收款的最长窗口
 * @param queryQps         查单限速（每秒）
 */
public record ChannelCapabilities(
		boolean close,
		boolean expire,
		boolean query,
		Duration maxPayWindow,
		int queryQps) {

	/** 未声明能力的渠道按最保守的口径：不关单、不传过期、可查单、7 天可付窗口、每秒 5 次。 */
	public static final ChannelCapabilities CONSERVATIVE = new ChannelCapabilities(false, false, true, Duration.ofDays(7), 5);
}
