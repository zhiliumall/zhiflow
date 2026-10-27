package com.zhiflow.upms.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.zhiflow.common.core.entity.base.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.NotNull;

/**
 * <p>
 * 菜单权限表
 * </p>
 *
 * @author
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "菜单")
public class SysMenu extends BaseEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

	/**
	 * 菜单名称
	 */
	@Schema(description = "菜单名称")
	@NotNull(message = "菜单名称不能为空")
	private String name;
	/**
	 * 菜单权限标识
	 */
	@Schema(description = "菜单权限标识")
	private String permission;
	/**
	 * 父菜单ID
	 */
	@Schema(description = "父菜单ID")
	@NotNull(message = "菜单父ID不能为空")
	private String parentId;
	/**
	 * 图标
	 */
	@Schema(description = "图标")
	private String icon;
	/**
	 * VUE页面
	 */
	@Schema(description = "VUE页面")
	private String component;
	/**
	 * 排序值
	 */
	@Schema(description = "排序值")
	private Integer sort;
	/**
	 * 菜单类型 （0菜单 1按钮）
	 */
	@NotNull(message = "菜单类型 （0菜单 1按钮）")
	private String type;
	/**
	 * 路由缓冲
	 */
	@Schema(description = "路由缓冲")
	private String keepAlive;
	/**
	 * 前端URL
	 */
	@Schema(description = "前端URL")
	private String path;

	@Schema(description = "	角色Id")
	@TableField(exist = false)
	private String roleId;
}
