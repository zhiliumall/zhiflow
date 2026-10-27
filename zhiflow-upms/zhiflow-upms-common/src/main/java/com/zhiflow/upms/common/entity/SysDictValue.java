package com.zhiflow.upms.common.entity;

import com.zhiflow.common.core.entity.base.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 字典项
 *
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "字典项")
public class SysDictValue extends BaseEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

	/**
	 *字典ID
	 */
	@Schema(description = "字典ID")
	private String dictId;
	/**
	 * 数据值
	 */
	@Schema(description = "数据值")
	private String value;
	/**
	 * 标签名
	 */
	@Schema(description = "标签名")
	private String label;
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
	 * 排序
	 */
	@Schema(description = "排序")
	private Integer sort;
	/**
	 * 备注信息
	 */
	@Schema(description = "备注信息")
	private String remarks;

}
