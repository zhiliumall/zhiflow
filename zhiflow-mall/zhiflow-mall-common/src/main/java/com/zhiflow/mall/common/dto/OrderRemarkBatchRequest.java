package com.zhiflow.mall.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** Request for a bounded batch order-remark operation. */
@Data
@Schema(description = "批量订单备注请求")
public class OrderRemarkBatchRequest {
    @Schema(description = "订单ID，去重后最多100个", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> orderIds;

    @Schema(description = "订单备注", requiredMode = Schema.RequiredMode.REQUIRED)
    private String remark;
}
