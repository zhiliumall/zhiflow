package com.zhiflow.upms.common.entity;

import com.zhiflow.common.core.entity.base.TenantEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.NotNull;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * <p>
 * 角色表
 * </p>
 *
 * @author
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "角色")
public class SysRole extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

	@Schema(description = "角色名称")
	@NotNull(message = "角色名称 不能为空")
	private String roleName;

	@Schema(description = "角色标识")
	@NotNull(message = "角色标识 不能为空")
	private String roleCode;

	@Schema(description = "角色描述")
	@NotNull(message = "角色描述 不能为空")
	private String roleDesc;

	@Schema(description = "数据权限")
	@NotNull(message = "数据权限类型 不能为空")
	private Integer dsType;

	private String dsScope;

}
