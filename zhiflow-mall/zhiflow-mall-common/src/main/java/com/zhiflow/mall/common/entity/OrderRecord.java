package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.TenantEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("order_record")
@Schema(description = "订单记录")
public class OrderRecord extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    @Schema(description = "订单ID")
    private String orderId;

    @Schema(description = "类型")
    private String recordType;

    @Schema(description = "来源")
    private String recordSource;

    @Schema(description = "内容")
    private String recordContent;
}
