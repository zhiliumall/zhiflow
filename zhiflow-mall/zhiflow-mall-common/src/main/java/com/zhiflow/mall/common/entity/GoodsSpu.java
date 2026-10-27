package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.annotation.HashidsId;
import com.zhiflow.common.core.entity.base.TenantEntity;
import com.zhiflow.common.core.mybatis.typehandler.ArrayStringTypeHandler;
import com.zhiflow.mall.common.dto.SpuSpecDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.type.JdbcType;

import java.math.BigDecimal;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * spu商品
 *
 * @date 2019-08-12 16:25:10
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("goods_spu")
@Schema(description = "spu商品")
public class GoodsSpu extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    @HashidsId
    private String id;

    /**
     * 店铺Id
     */
    @Schema(description = "店铺Id")
    private String shopId;
    /**
     * spu编码
     */
    @Schema(description = "spu编码")
    private String spuCode;
    /**
     * spu名字
     */
    @Schema(description = "spu名字")
    private String name;

    /**
     * 卖点
     */
    @Schema(description = "卖点")
    private String sellPoint;
    /**
     * 描述
     */
    @Schema(description = "描述")
    private String description;
    /**
     * 商城一级分类ID
     */
    @Schema(description = "商城一级分类ID")
    private String categoryFirst;
    /**
     * 商城二级分类ID
     */
    @Schema(description = "商城二级分类ID")
    private String categorySecond;
    /**
     * 商城商品三级分类ID
     */
    @Schema(description = "商城商品三级分类ID")
    private String categoryThird;
    /**
     * 商品视频地址
     */
    @Schema(description = "商品视频地址")
    private String videoUrl;
    /**
     * 商品主图
     */
    @Schema(description = "商品主图")
    @TableField(typeHandler = ArrayStringTypeHandler.class, jdbcType = JdbcType.VARCHAR)
    private String[] picUrls;
    /**
     * 是否上架（0否 1是）
     */
    @Schema(description = "是否上架（0否 1是）")
    private String shelf;
    /**
     * 排序字段
     */
    @Schema(description = "排序字段")
    private Integer sort;
    /**
     * 最低价
     */
    @Schema(description = "最低价")
    private BigDecimal priceDown;
    /**
     * 最高价
     */
    @Schema(description = "最高价")
    private BigDecimal priceUp;
    /**
     * 销量
     */
    @Schema(description = "销量")
    private Integer saleNum;
    /**
     * 0统一规格；1多规格
     */
    @Schema(description = "0统一规格；1多规格")
    private String specType;
    /**
     * 运费类型（0：包邮；1：固定邮费；2：运费模板）
     */
    @Schema(description = "运费类型（0：包邮；1：固定邮费；2：运费模板）")
    private String freightType;
    /**
     * 运费金额（固定邮费必填）
     */
    @Schema(description = "运费金额（固定邮费必填）")
    private BigDecimal freightAmount;
    /**
     * 审核状态（0审核中 1审核通过 2审核不通过）
     */
    @Schema(description = "审核状态（0审核中 1审核通过 2审核不通过）")
    private String verifyStatus;

    /**
     * 品牌ID
     */
    @Schema(description = "品牌ID")
    private String brandId;

    /**
     * 浏览量
     */
    @Schema(description = "浏览量")
    private Integer viewNum;

    /**
     * 库存预警线阈值（V68）：聚合 SKU 库存低于此值即预警；为空时读侧兜底默认 20。
     */
    @Schema(description = "库存预警线阈值")
    private Integer stockWarnLine;

	/**
	 * 库存
	 */
	@TableField(exist = false)
	private Integer stock;
	/**
	 * 商品主图URL（用于简化显示）
	 */
	@TableField(exist = false)
	private String picUrl;
	/**
	 * 销售价格（用于简化显示）
	 */
	@TableField(exist = false)
	private BigDecimal salesPrice;
	/**
	 * 分类ID（用于查询）
	 */
	@TableField(exist = false)
	private List<String> categoryId;
	/**
	 * 库存预警筛选信号（非持久化，查询专用）。为 true 时 selectPage1 追加谓词：
	 * 聚合 SKU 库存 &lt;= COALESCE(stock_warn_line, 20)，只返回需预警的商品。
	 */
	@Schema(description = "库存预警筛选：true 仅返回聚合库存低于预警线的商品（查询专用）")
	@TableField(exist = false)
	private Boolean stockWarning;

    @TableField(typeHandler = ArrayStringTypeHandler.class, jdbcType = JdbcType.VARCHAR)
    private String[] label;
    /**
     * 上架平台 H5\APP\MA\H5-PC\H5-WX
     */
    @TableField(typeHandler = ArrayStringTypeHandler.class, jdbcType = JdbcType.VARCHAR)
    private String[] platform;

    private String parameterJson;


    @TableField(exist = false)
    private List<GoodsSku> skus;

    /**
     * 规格摘要（首个SKU规格值拼接，如 40mg×90片）
     */
    @Schema(description = "规格摘要")
    @TableField(exist = false)
    private String specSummary;

    @TableField(exist = false)
    private List<SpuSpecDTO> spuSpec;


    @TableField(exist = false)
    private GoodsBrand goodsBrand;

}
