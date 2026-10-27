package com.zhiflow.mall.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** Per-order outcomes for a batch remark operation. */
@Data
@Schema(description = "批量订单备注结果")
public class OrderRemarkBatchResult {
    private int requested;
    private int updated;
    private int unchanged;
    private int failed;
    private List<Item> results;

    @Data
    @Schema(description = "单个订单备注结果")
    public static class Item {
        private String orderId;
        /** UPDATED, UNCHANGED or FAILED */
        private String status;
        private String code;
        private String message;
        private String remark;
    }
}
