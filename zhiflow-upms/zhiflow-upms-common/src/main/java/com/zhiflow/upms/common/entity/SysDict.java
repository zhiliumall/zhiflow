package com.zhiflow.upms.common.entity;

import com.zhiflow.common.core.entity.base.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 字典表
 * </p>
 *
 * @author
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "字典")
public class SysDict extends BaseEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

	/**
	 * 类型
	 */
	@Schema(description = "类型")
	private String type;
	/**
	 * 描述
	 */
	@Schema(description = "描述")
	private String description;
	/**
	 * 备注信息
	 */
	@Schema(description = "备注信息")
	private String remarks;

}
