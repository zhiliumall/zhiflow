package com.zhiflow.upms.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.zhiflow.common.core.entity.base.TenantEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.NotNull;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * <p>
 * 日志表
 * </p>
 *
 * @author
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "日志")
public class SysLog extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

	/**
	 * 日志类型
	 */
	@Schema(description = "日志类型")
	@NotNull(message = "日志类型不能为空")
	private String type;
	/**
	 * 日志标题
	 */
	@Schema(description = "标题")
	@NotNull(message = "日志标题不能为空")
	private String title;
	/**
	 * 操作内容（S4）：由 {@code @SysLog(content = "SpEL 模板")} 渲染出的人话，如
	 * 「删除优惠券模板「双十一满减」，连带作废已发放 1910 张」。
	 *
	 * <p>与 {@link #title} 的分工：title 是注解上的静态字符串，用于按操作类型筛选
	 * （{@code SysLogController.getLogPage} 走 {@code Wrappers.query} 精确匹配，
	 * 不能被每条唯一的内容覆盖）；content 才是「这次具体做了什么」。
	 *
	 * <p>未填模板或渲染失败时为 null，行为与 S4 改造前一致。
	 */
	@Schema(description = "操作内容（人话描述）")
	private String content;
	/**
	 * 创建者
	 */
	@Schema(description = "创建者ID")
	private String createId;
	/**
	 * 创建者
	 */
	@Schema(description = "创建者名")
	@TableField(fill = FieldFill.INSERT)
	private String createBy;
	/**
	 * 操作IP地址
	 */
	@Schema(description = "IP地址")
	private String remoteAddr;
	/**
	 * 用户代理
	 */
	@Schema(description = "用户代理")
	private String userAgent;
	/**
	 * 请求URI
	 */
	@Schema(description = "请求URI")
	private String requestUri;
	/**
	 * 操作方式
	 */
	@Schema(description = "操作方式")
	private String method;
	/**
	 * 操作提交的数据
	 */
	@Schema(description = "提交的数据")
	private String params;
	/**
	 * 执行时间
	 */
	@Schema(description = "执行时间")
	private Long time;

	/**
	 * 异常信息
	 */
	@Schema(description = "异常信息")
	private String exception;

	/**
	 * 服务ID
	 */
	@Schema(description = "服务ID")
	private String serviceId;

}
