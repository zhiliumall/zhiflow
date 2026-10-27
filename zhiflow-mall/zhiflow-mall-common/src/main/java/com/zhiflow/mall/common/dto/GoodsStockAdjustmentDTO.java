package com.zhiflow.mall.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "SKU库存调整请求")
public class GoodsStockAdjustmentDTO {

    @NotBlank
    private String skuId;

    @NotNull
    @Schema(description = "库存变动量，补货为正数、盘亏为负数，不能为0")
    private Integer changeQuantity;

    @Schema(description = "调整说明")
    private String remark;
}
