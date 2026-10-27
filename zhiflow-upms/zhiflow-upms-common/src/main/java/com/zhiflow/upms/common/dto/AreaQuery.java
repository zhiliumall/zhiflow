package com.zhiflow.upms.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * {@code TableAreaApi#getList} 的<b>请求</b>投影 —— 取代把 {@code TableArea} 实体当请求体。
 *
 * <h2>为什么是「等值过滤条件」的全量，而不是只留 id / pid</h2>
 * <p>服务端是 {@code tableAreaService.list(Wrappers.query(entity))}：
 * <b>请求里每一个非 null 字段都会被拼进 WHERE 做等值匹配</b>。
 * 本仓两个调用方只设过 {@code id}（mall-api {@code FreightTemplatController} 按区县反查上级）
 * 与 {@code pid}（H5 级联地址选择器，实测 {@code /mallapi/tablearea/list?pid=xxx}），
 * 但 mall-api 的 {@code TableAreaController#getList} 是把整个实体交给 Spring MVC 表单绑定的
 * <b>公开透传端点</b> —— 任何客户端理论上都能传 {@code name} / {@code level} / {@code pinyin} 过滤，
 * 而 PC / App 仓库不在本仓、核不到。
 *
 * <p>收窄成 2 个具名字段的风险不是编译错误，是<b>查询条件静默失效</b>：结果集变多或变少、
 * 没有任何异常、只有用户看到地址选不对。所以这里做的是**纯类型替换**：字段与
 * {@code TableArea} 的可过滤列逐一对应，行为逐字节不变，只是契约不再挂在 MyBatis 实体上
 * （那才是本轮要修的病 —— 服务端改列名时契约不报错）。
 */
@Data
public class AreaQuery implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private String id;

	private String pid;

	private String shortname;

	private String name;

	private String mergename;

	private Integer level;

	private String pinyin;

	private String code;

	private String zip;

	private String first;

	private String lng;

	private String lat;
}
