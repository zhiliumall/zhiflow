package com.zhiflow.mall.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.common.entity.PayOrderStateLog;

/** 支付单状态流水（V118，只追加）。pay_order 只在 mall-api 写，只有这一份。 */
public interface PayOrderStateLogMapper extends BaseMapper<PayOrderStateLog> {
}
