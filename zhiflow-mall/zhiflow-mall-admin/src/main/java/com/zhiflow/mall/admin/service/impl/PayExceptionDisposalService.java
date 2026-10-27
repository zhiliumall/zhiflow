package com.zhiflow.mall.admin.service.impl;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.common.security.util.SecurityUtils;
import com.zhiflow.mall.admin.mapper.PayExceptionMapper;
import com.zhiflow.mall.common.entity.PayException;
import com.zhiflow.mall.common.trade.payexception.PayExceptionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 差错池最小版：后台只有一个动作「标记已处理」——OPEN → RESOLVED，必填备注、记处理人。
 *
 * <p>池里只有「钱到了、订单收不了」的两类（LATE_PAID 取消后才到账、DUP_PAY 同一订单重复付款）；
 * 退款由人在渠道商户后台手工操作，做完回来标记。系统不自动退、不开售后单、不恢复订单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayExceptionDisposalService {

	/** resolution 列的取值：人工在渠道后台处理后标记。 */
	static final String MANUAL_HANDLED = "MANUAL_HANDLED";

	private final PayExceptionMapper payExceptionMapper;

	public void resolve(String id, String note) {
		if (StrUtil.isBlank(note)) {
			throw new BizException("必须填写处理备注（例如：已在支付渠道商户后台原路退款，退款单号 xxx）");
		}
		PayException ex = payExceptionMapper.selectById(id);
		if (ex == null) {
			throw new BizException("差错不存在");
		}
		if (!PayExceptionStatus.OPEN.name().equals(ex.getStatus())) {
			throw new BizException("只有「待处理」的差错可以标记（当前 " + ex.getStatus() + "）");
		}
		String by = operator();
		if (payExceptionMapper.finish(id, PayExceptionStatus.RESOLVED.name(), MANUAL_HANDLED, by,
				StrUtil.maxLength(note.trim(), 255)) == 0) {
			throw new BizException("该差错已被处理，请刷新");
		}
		log.warn("差错 {}（{} 订单 {}）标记已处理，操作人 {}：{}", id, ex.getType(), ex.getOrderNo(), by, note);
	}

	private static String operator() {
		try {
			BaseUser u = SecurityUtils.getUser();
			return u == null ? "ADMIN" : StrUtil.maxLength(StrUtil.blankToDefault(u.getUsername(), u.getId()), 32);
		}
		catch (Exception e) {
			return "ADMIN";
		}
	}
}
