package com.zhiflow.upms.common.entity;

import com.zhiflow.common.core.entity.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * <p>
 * 角色菜单表
 * </p>
 *
 * @author
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SysRoleMenu extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

	/**
	 * 角色ID
	 */
	private String roleId;
	/**
	 * 菜单ID
	 */
	private String menuId;

}
