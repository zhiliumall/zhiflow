package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.TenantEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("goods_parameter")
@Schema(description = "商品参数")
public class GoodsParameter extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String parameterTemplateId;

    /**
     * 名称
     */
    @Schema(description = "名称")
    private String name;

    private Integer sort;
    /**
     * 0:single
     * 1:multi
     * 2:input
     */
    private Integer type;

    @TableField(exist = false)
    private List<GoodsParameterValue> goodsParameterValueList;

}
