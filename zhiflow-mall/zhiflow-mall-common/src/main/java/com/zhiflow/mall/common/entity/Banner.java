package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.TenantEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 首页轮播图（S-10）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("banner")
@Schema(description = "首页轮播图")
public class Banner extends TenantEntity {

	@Schema(description = "PK")
	@TableId(type = IdType.ASSIGN_ID)
	private String id;

	@NotBlank(message = "标题不能为空")
	@Size(max = 64, message = "标题最多 64 个字")
	@Schema(description = "标题（后台识别用，H5 不展示）")
	private String title;

	@NotBlank(message = "图片不能为空")
	@Size(max = 500, message = "图片地址过长")
	@Schema(description = "图片地址")
	private String imageUrl;

	@Size(max = 500, message = "跳转地址过长")
	@Schema(description = "点击跳转地址；空 = 不跳转")
	private String linkUrl;

	@Schema(description = "排序，小的在前")
	private Integer sort;

	@Pattern(regexp = "[01]", message = "启用状态只能是 0 或 1")
	@Schema(description = "1 启用 / 0 停用")
	private String enable;
}
