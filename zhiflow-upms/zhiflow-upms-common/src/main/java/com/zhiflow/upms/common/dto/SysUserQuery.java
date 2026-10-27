package com.zhiflow.upms.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * {@code UserApi#getInsideUserList} 的请求投影 —— 取代把 {@code SysUser} 实体当请求体。
 *
 * <h2>为什么这次可以放心收窄（与 {@link AreaQuery} 不同）</h2>
 * <p>服务端不是 {@code Wrappers.query(entity)}，而是一条**写死的 XML**
 * （{@code SysUserMapper.xml#listSysUser}）。开源版它的 {@code <where>} 里只剩 {@code id} / {@code type} 两个 {@code <if>}
 * （店员短信 / 推送 / 客服开关与店铺随功能删除）。
 * 也就是说请求里别的字段<b>今天就已经被忽略</b>，收窄不会改变任何客户端看到的结果 ——
 * 哪怕 mall-api 的 {@code /customerservice/list} 是个把整个实体做表单绑定的公开端点。
 *
 * <p>之后的两处行为修正（都在那条 XML 里）：
 * <ul>
 *   <li>2026-09-14 补上了 {@code id} 的 {@code <if>}（此前按 id 查拿回的是全部用户）。</li>
 *   <li>2026-09-29 加了<b>无条件</b>的 {@code del_flag = '0'}：只返回未删除的用户。本类不带 delFlag —— 调用方拿不到已删用户，也不需要。</li>
 * </ul>
 */
@Data
public class SysUserQuery implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	/** 按用户 id 精确过滤（2026-09-14 起生效，见类注释）。 */
	private String id;

	/** 账号类型：'-1' 系统管理员 '1' 商城管理员。 */
	private String type;

}
