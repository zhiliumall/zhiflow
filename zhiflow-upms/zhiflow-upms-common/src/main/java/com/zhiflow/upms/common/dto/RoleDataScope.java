package com.zhiflow.upms.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * {@code RoleApi#getDsTypeMinRole} 的投影 —— 取代把 {@code SysRole} 实体当契约。
 *
 * <p>唯一调用方是 {@code zhiflow-common-data} 的 {@code DataScopeInterceptor}
 * （2026-09-14 全仓核过），它只读这两个字段来决定 SQL 要不要追加机构过滤：
 * {@code dsType} 选分支，{@code dsScope} 在「自定义」分支里按逗号切成机构 id 列表。
 * {@code id} / {@code roleName} / {@code roleCode} / {@code roleDesc} / {@code tenantId} 没人读。
 *
 * <p>「找不到角色」这个态由 {@code R.ok(null)} 表达 —— 服务端是
 * {@code listByIds(...).stream().min(...).orElse(null)}，空列表时确实会给 null。
 * ⚠️ 今天的调用方没有判空就 {@code getDsType()}，那是既有行为，本轮不改。
 */
@Data
public class RoleDataScope implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	/** 数据权限类型（{@code DataScopeTypeEnum}）：0 全部 / 1 自定义 / 2 本级及下级 / 3 仅本级。 */
	private Integer dsType;

	/** 自定义数据权限的机构 id，逗号分隔。仅 {@code dsType == CUSTOM} 时有意义。 */
	private String dsScope;
}
