package com.zhiflow.upms.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * {@code SysDictApi#getSysDictList} 的响应投影 —— 取代把 {@code SysDict} 实体当契约。
 *
 * <p>字段与 {@link SysDictQuery} 同源。调用链是纯透传到前端（mall-api
 * {@code SysDictController}），Java 侧一个字段都不读，所以按实体自己声明的字段全带，
 * 只丢掉 {@code BaseEntity} 的三个基础设施字段。
 */
@Data
public class SysDictInfo implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private String id;

	private String type;

	private String description;

	private String remarks;
}
