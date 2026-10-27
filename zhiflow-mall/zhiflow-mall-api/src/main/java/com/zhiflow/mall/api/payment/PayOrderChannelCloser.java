package com.zhiflow.mall.api.payment;

import com.zhiflow.mall.api.mapper.PayOrderMapper;
import com.zhiflow.mall.api.mapper.PayOrderStateMachineMapper;
import com.zhiflow.mall.common.entity.PayOrder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * P6 的可选动作：本地关单提交之后，渠道支持关单（{@link ChannelCapabilities#close()}）才去渠道关（P6、14.1）。
 *
 * <p>调渠道的 HTTP 一律在事务提交之后、锁之外做；关成了才打 {@code channel_closed=true}，关不成（或渠道不支持）支付单
 * 保持 {@code channel_closed=false}，由补单任务在渠道可付窗口内继续扫——迟到的钱走 P7 兜底。
 * 业务侧不写「如果是某渠道就关单」：改配置 {@code pay.channel.<code>.cap.close=true} 即生效（14.1 验收）。
 */
@Slf4j
@Component
public class PayOrderChannelCloser {

	private final PaymentChannelRegistry registry;

	private final ChannelCapabilityResolver capabilities;

	private final PayOrderMapper payOrderMapper;

	private final PayOrderStateMachineMapper stateMachineMapper;

	public PayOrderChannelCloser(PaymentChannelRegistry registry, ChannelCapabilityResolver capabilities,
			PayOrderMapper payOrderMapper, PayOrderStateMachineMapper stateMachineMapper) {
		this.registry = registry;
		this.capabilities = capabilities;
		this.payOrderMapper = payOrderMapper;
		this.stateMachineMapper = stateMachineMapper;
	}

	/** 当前有事务就排到提交之后执行，否则立即执行（租户上下文沿用调用线程）。 */
	public void closeAfterCommit(String channelCode, String payNo) {
		if (!capabilities.of(channelCode).close()) {
			return;
		}
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					closeNow(channelCode, payNo);
				}
			});
		}
		else {
			closeNow(channelCode, payNo);
		}
	}

	/** @return 渠道确认已关单并打上标记 */
	boolean closeNow(String channelCode, String payNo) {
		PaymentChannel channel = registry.find(channelCode).orElse(null);
		PayOrder p = payOrderMapper.selectOne(Wrappers.<PayOrder>lambdaQuery().eq(PayOrder::getPayNo, payNo));
		if (channel == null || p == null) {
			return false;
		}
		try {
			if (!channel.close(p)) {
				log.warn("渠道关单未成功，支付单保持 channel_closed=false 由补单任务继续扫：payNo={} channel={}", payNo,
						channelCode);
				return false;
			}
		}
		catch (Exception e) {
			log.warn("渠道关单异常，支付单保持 channel_closed=false 由补单任务继续扫：payNo={} channel={}", payNo,
					channelCode, e);
			return false;
		}
		return stateMachineMapper.markChannelClosed(payNo) > 0;
	}
}
