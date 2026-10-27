package com.zhiflow.mall.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * 订单记录类型枚举
 */
public enum OrderRecordTypeEnum implements IEnum<String> {
    CREATE("订单生成"),
    PAY("用户付款完成"),
    CANCEL("订单取消"),
    DELIVERY("商家发货"),
    RECEIVE("确认收货"),
    PROCESS("处理订单"),
    ESTIMATED("预估到达"),
    PACKAGE("仓库打包"),
    SUBMIT("已下单"),
    OUT("出库"),
    PRINT("打印订单"),
    COLLECTED("已揽收"),
    IN_TRANSIT("在途中"),
    DELIVERED("已签收"),
    PROBLEM("问题件"),
    FORWARDED("转寄"),
    URGE_SHIP("催发货"),
    EDIT_PRICE("商家改价"),
    ;

    private final String value;

    OrderRecordTypeEnum(String value) {
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }
}
