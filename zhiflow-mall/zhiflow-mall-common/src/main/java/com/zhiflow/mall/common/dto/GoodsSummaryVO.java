package com.zhiflow.mall.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品摘要投影。客服工作台「相关商品」Tab 用，按 SPU 维度聚合。
 */
@Data
@Schema(description = "商品摘要")
public class GoodsSummaryVO {

    @Schema(description = "SPU ID")
    private String spuId;

    @Schema(description = "商品名")
    private String name;

    @Schema(description = "主图 URL")
    private String image;

    @Schema(description = "价格（最低规格价）")
    private BigDecimal price;

    @Schema(description = "商品所属品类")
    private String categoryName;

    @Schema(description = "该用户在已支付有效订单中的累计下单件数")
    private Integer purchaseTimes;
}
