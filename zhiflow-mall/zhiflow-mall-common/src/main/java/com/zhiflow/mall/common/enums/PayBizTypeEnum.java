package com.zhiflow.mall.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 支付单的业务类型，对应 {@code pay_order.biz_type}。
 *
 * <p>取值直接对上此前 5 处各写一遍 {@code paymentType} 分支链的那 5 个业务入口：
 * 商品订单、会员充值、余额充值、收银台、分销保证金。
 * 支付编排层按本枚举把入账动作派发给对应的
 * {@code PaymentSettlementHandler}，业务与渠道从此互不认识。
 */
public enum PayBizTypeEnum implements IEnum<String> {

    /** 商品订单（{@code order_info}）。 */
    ORDER("ORDER", "商品订单"),

    /** 会员充值。 */
    VIP_RECHARGE("VIP_RECHARGE", "会员充值"),

    /** 余额充值。 */
    BALANCE("BALANCE", "余额充值"),

    /** 收银台单据。 */
    CASHIER("CASHIER", "收银台"),

    /** 分销保证金。 */
    DEPOSIT("DEPOSIT", "分销保证金");

    private final String value;

    private final String desc;

    @JsonCreator
    PayBizTypeEnum(final String value, final String desc) {
        this.value = value;
        this.desc = desc;
    }

    @Override
    public String getValue() {
        return this.value;
    }

    @JsonValue
    public String getDesc() {
        return this.desc;
    }

    public static String getDesc(String value) {
        for (PayBizTypeEnum item : values()) {
            if (item.getValue().equals(value)) {
                return item.getDesc();
            }
        }
        return "";
    }
}
