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
@TableName("goods_parameter_value")
@Schema(description = "商品参数")
public class GoodsParameterValue extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 参数Id
     */
    @Schema(description = "参数Id")
    private String parameterId;
    /**
     * 名称
     */
    @Schema(description = "名称")
    private String name;
    private Integer sort;

}
