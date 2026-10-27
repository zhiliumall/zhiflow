package com.zhiflow.upms.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.BaseEntity;
import com.zhiflow.common.core.sensitive.Sensitive;
import com.zhiflow.common.core.sensitive.SensitiveTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * <p>
 * 租户机构管理
 * </p>
 *
 * @author
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "租户管理")
@TableName(value = "sys_tenant")
public class SysTenant extends BaseEntity {

	@Schema(description = "PK")
	@TableId(type = IdType.INPUT)
	private String id;
	/**
	 * 租户名称
	 */
	@Schema(description = "租户名称")
	@NotNull(message = "租户名称不能为空")
	private String name;
	/**
	 * 排序
	 */
	@Schema(description = "排序")
	@NotNull(message = "排序不能为空")
	private Integer sort;
	/**
	 * 租户编码
	 */
	@Schema(description = "租户编码")
	@NotNull(message = "租户编码不能为空")
	private String code;
	/**
	 * 类型：1租户；-1平台系统管理
	 */
	@Schema(description = "类型：1租户；-1平台系统管理")
	private String type;
	/**
	 * 电话
	 */
	@Sensitive(type = SensitiveTypeEnum.MOBILE_PHONE)
	@Schema(description = "电话")
	private String phone;
	/**
	 * 传真
	 */
	@Schema(description = "传真")
	private String fax;
	/**
	 * 邮箱
	 */
	@Schema(description = "邮箱")
	private String email;
	/**
	 * 地址
	 */
	@Schema(description = "地址")
	private String address;
	/**
	 * 备注
	 */
	@Schema(description = "备注")
	private String remarks;

	/**
	 * 状态 0:正常，9:冻结
	 */
	@Schema(description = "状态 0:正常，9:冻结")
	private String status;

	/**
	 * logo
	 */
	@Schema(description = "logo")
	private String logo;
	/**
	 * 官方网址
	 */
	@Schema(description = "官方网址")
	private String homeUrl;
	/**
	 * 到期时间
	 */
	@Schema(description = "到期时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private LocalDateTime expireTime;
	/**
	 * 公司名
	 */
	@Schema(description = "公司名")
	@Size(max = 100, message = "公司主体全称不能超过 100 个字符")
	private String companyName;
	/**
	 * 公司信用代码
	 */
	@Schema(description = "公司信用代码")
	private String companyCode;
	/**
	 * 公司营业执照
	 */
	@Schema(description = "公司营业执照")
	private String companyLicense;

	/**
	 * 品牌短名。界面标题/抬头用，空则回退 {@link #name}。
	 * <p>
	 * 为什么不直接用 {@code name}：{@code name} 是租户**组织名**不是品牌名，例如可能是
	 * 「某某公司全国运营中心」这类机构名称，直接拼到前端标题上会很怪，必须分开维护。
	 */
	@Schema(description = "品牌短名（空则回退租户名）")
	@Size(max = 64, message = "品牌名不能超过 64 个字符")
	private String brandName;
	/**
	 * 品牌英文名
	 */
	@Schema(description = "品牌英文名")
	@Size(max = 128, message = "品牌英文名不能超过 128 个字符")
	private String brandNameEn;
	/**
	 * 法人主体英文全称。与 {@link #companyName} 同属合规信息，印在订单凭证上；
	 * 两者皆空时凭证**不渲染**该块——宁可没有主体，也不能印错主体。
	 */
	@Schema(description = "法人主体英文全称")
	@Size(max = 255, message = "公司主体英文全称不能超过 255 个字符")
	private String companyNameEn;
	/**
	 * 浏览器页签图标 URL，空则回退 {@link #logo}
	 */
	@Schema(description = "页签图标（空则回退 logo）")
	private String brandFavicon;

	@TableField(exist = false)
	private String username;

	@TableField(exist = false)
	private String password;

	/**
	 * {@link #password} 是否已是 BCrypt 编码值（开源版目前没有设置它的入口），为 true 时建管理员不再二次编码、
	 * 开通短信也不下发该值。只在服务端内部设置，不接受请求体传入。
	 */
	@TableField(exist = false)
	@JsonIgnore
	@Schema(hidden = true)
	private Boolean passwordEncoded;

	@TableField(exist = false)
	private String userPhone;
}
