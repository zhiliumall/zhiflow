package com.zhiflow.common.core.entity.base;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户实体基类 - 在 BaseEntity 基础上增加 tenantId 字段
 * <p>
 * 大多数租户业务实体继承此类即可自动获得：tenantId, delFlag, createTime, updateTime
 *
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class TenantEntity extends BaseEntity {

	/**
	 * 所属租户
	 */
	@Schema(description = "所属租户")
	@TableField(fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
	private String tenantId;

}
