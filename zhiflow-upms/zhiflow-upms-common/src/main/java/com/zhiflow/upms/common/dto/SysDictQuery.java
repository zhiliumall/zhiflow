package com.zhiflow.upms.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * {@code SysDictApi#getSysDictList} 的请求投影 —— 取代把 {@code SysDict} 实体当请求体。
 *
 * <p>服务端是 {@code sysDictService.list(Wrappers.query(sysDict))}：非 null 字段全部拼进 WHERE。
 * 唯一调用链是 mall-api 的 {@code SysDictController#getListPage}（{@code POST /mallapi/dict/list}，
 * {@code mustLogin=false} 的公开端点，请求体由前端给）→ {@code SignConfigServiceImpl} 透传。
 * 前端传什么核不到，所以按 {@code SysDict} <b>自己声明</b>的字段全带（口径同 {@link AreaQuery}）。
 *
 * <p>刻意不带 {@code BaseEntity} 的 {@code delFlag} / {@code createTime} / {@code updateTime}：
 * 前两者是逻辑删除与审计列，拿时间戳做<b>等值</b>过滤没有任何业务含义
 * （而且 {@code @TableLogic} 的 {@code delFlag} 由 MyBatis-Plus 自己加条件，外部传值只会打架）。
 */
@Data
public class SysDictQuery implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private String id;

	/** 字典类型。 */
	private String type;

	private String description;

	private String remarks;
}
