package com.zhiflow.mall.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 支付方式 / 渠道码。与 {@code pay_config.type}、{@code order_info.payment_type}、
 * {@code pay_order.channel_code} 同一套取值，是渠道码的唯一真源。
 */
public enum PaymentTypeEnum implements IEnum<String>{
    WECHAT("1","微信支付"),
    ALIPAY("2","支付宝支付");

    @JsonCreator
    PaymentTypeEnum(final String value, final String desc) {
        this.value = value;
        this.desc = desc;
    }
    private String value;
    private String desc;

    @Override
    public String getValue() {
        return this.value;
    }

    @JsonValue
    public String getDesc(){
        return this.desc;
    }

    public static String getDesc(String value) {
        for (PaymentTypeEnum item : values()) {
            if (item.getValue().equals(value)) {
                return item.getDesc();
            }
        }
        return "";
    }
}
