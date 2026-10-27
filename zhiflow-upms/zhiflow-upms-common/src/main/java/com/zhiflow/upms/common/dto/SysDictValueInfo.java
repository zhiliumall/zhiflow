package com.zhiflow.upms.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * {@code SysDictApi#getInsideDictByType} 的响应投影 —— 取代把 {@code SysDictValue} 实体当契约。
 *
 * <p>这条边承载的是<b>全站国际化词条</b>：H5 启动时拉 {@code /mallapi/dict/type/{lang}}，
 * 把每一项按 {@code item.value → item.label} 折成语言包再 {@code mergeLocaleMessage}
 * （H5 前端的 {@code utils/config-cache.ts} 实测）。
 * 也就是说 {@code value} / {@code label} 缺一个，整站文案就回落本地包 —— 必须在。
 *
 * <p>其余字段是纯透传给前端的（PC / App 读了什么核不到），按实体自己声明的字段全带，
 * 只丢掉 {@code BaseEntity} 的 {@code delFlag} / {@code createTime} / {@code updateTime}。
 */
@Data
public class SysDictValueInfo implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private String id;

	/** 所属字典 ID。 */
	private String dictId;

	/** 数据值。国际化场景下是词条 key。 */
	private String value;

	/** 标签名。国际化场景下是词条译文。 */
	private String label;

	private String type;

	private String description;

	private Integer sort;

	private String remarks;
}
