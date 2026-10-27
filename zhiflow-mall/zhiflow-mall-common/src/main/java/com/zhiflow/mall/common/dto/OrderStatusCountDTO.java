package com.zhiflow.mall.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 订单状态计数DTO
 *
 */
@Data
@Schema(description = "订单状态计数")
public class OrderStatusCountDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "待付款订单数量")
    private Long pendingPayment;

    @Schema(description = "待发货订单数量")
    private Long pendingDelivery;

    @Schema(description = "待收货订单数量")
    private Long pendingReceipt;

    @Schema(description = "已完成订单数量")
    private Long completed;

    public OrderStatusCountDTO() {
        this.pendingPayment = 0L;
        this.pendingDelivery = 0L;
        this.pendingReceipt = 0L;
        this.completed = 0L;
    }

    /**
     * 构造函数，接受数据库查询返回的Map
     * @param countMap 包含各状态订单数量的Map
     */
    public OrderStatusCountDTO(java.util.Map<String, Object> countMap) {
        this();
        if (countMap != null) {
            this.pendingPayment = getLongValue(countMap.get("0"));
            this.pendingDelivery = getLongValue(countMap.get("1"));
            this.pendingReceipt = getLongValue(countMap.get("2"));
            this.completed = getLongValue(countMap.get("3"));
        }
    }

    private Long getLongValue(Object obj) {
        if (obj instanceof Number number) {
            return number.longValue();
        } else if (obj instanceof String string) {
            try {
                return Long.parseLong(string);
            } catch (NumberFormatException _) {
                return 0L;
            }
        } else if (obj != null) {
            return Long.valueOf(obj.toString());
        }
        return 0L;
    }
}