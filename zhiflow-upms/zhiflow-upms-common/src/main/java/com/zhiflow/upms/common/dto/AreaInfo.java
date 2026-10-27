package com.zhiflow.upms.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * {@code TableAreaApi#getList} 的<b>响应</b>投影 —— 取代把 {@code TableArea} 实体当契约。
 *
 * <p>字段与 {@link AreaQuery} 同源（同一张 {@code table_area} 的列）。
 * 本仓 Java 侧只读 {@code id}（H5 地址选择器）、{@code name}（同上）、{@code pid}
 * （{@code FreightTemplatController} 反查上级），但 mall-api 的 {@code /tablearea/list}
 * 是把整行交给前端的公开透传端点，PC / App 读了什么核不到 —— 理由与 {@link AreaQuery} 相同，
 * 按「实体自己声明的列」全带，做纯类型替换。
 */
@Data
public class AreaInfo implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private String id;

	private String pid;

	private String shortname;

	private String name;

	private String mergename;

	/** 层级 0 1 2 省市区县。 */
	private Integer level;

	private String pinyin;

	/** 长途区号。 */
	private String code;

	private String zip;

	private String first;

	private String lng;

	private String lat;
}
