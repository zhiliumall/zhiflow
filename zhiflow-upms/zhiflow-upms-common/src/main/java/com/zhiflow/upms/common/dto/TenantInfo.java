package com.zhiflow.upms.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * {@code TenantApi#list} / {@code #getById} 的投影 —— 取代把 {@code SysTenant} 实体当契约。
 *
 * <h2>字段是「服务端真的产出 ∩ 调用方真的读取」</h2>
 * <p>2026-09-14 全仓逐点核过 43 个调用点（{@code list} 36 + {@code getById} 7）：
 * <ul>
 * <li>{@code id} —— 84 处。批处理 Job 遍历租户时唯一用到的东西（{@code TenantContextHolder.setTenantId}）；</li>
 * <li>{@code status} / {@code expireTime} —— mall-api {@code OrderInfoController} 下单前的锁定与欠费校验；</li>
 * <li>{@code brandName} / {@code name} —— im 的 AI 客服品牌名兜底链、mall-api 邮件验证码抬头。</li>
 * </ul>
 *
 * <h2>为什么 list 与 getById 共用一个消息（而不是各拆一个）</h2>
 * <p>两者是同一张表的同一行，字段集合有交集（{@code id}）。
 * 拆成 {@code TenantSummary}(2 字段) 与 {@code Tenant}(6 字段) 会让人以为是两个领域概念，
 * 而且以后任何一个 Job 想多读一个字段（例如跳过被锁租户）就要再改一次 proto。
 * 合并的代价是 {@code list} 的载荷多 5 个 optional 字段 —— {@code sys_tenant} 只有 8 行，可忽略。
 *
 * <h2>刻意不带的字段</h2>
 * <p>{@code phone}（{@code @Sensitive}）、{@code fax} / {@code email} / {@code address} / {@code remarks} /
 * {@code logo} / {@code homeUrl} / {@code companyName} / {@code companyCode} /
 * {@code companyLicense} / {@code brandNameEn} / {@code companyNameEn} / {@code brandFavicon} /
 * {@code sort} / {@code code} / {@code type}：**没有任何跨进程调用方读它们**。
 * {@code phone} 尤其不能顺手带上 —— 它今天走 REST 时被 Jackson 脱敏成 {@code 138****1234}，
 * 而 codec 不过 Jackson，1:1 映射会让明文第一次在进程间流通。不带 = 这条边上不存在这个问题。
 * {@code username}/{@code password}/{@code userPhone} 是 {@code @TableField(exist=false)} 的建租户入参，
 * 查询路径上恒为 null。
 */
@Data
public class TenantInfo implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	/** 租户 ID。批处理 Job 定位租户上下文的唯一依据。 */
	private String id;

	/** 租户组织名。品牌名为空时的回退（mall-api 邮件抬头）。 */
	private String name;

	/** 品牌短名。空则回退 {@link #name}。 */
	private String brandName;

	/** 状态 0:正常，9:冻结。下单前校验商城是否被锁。 */
	private String status;

	/** 到期时间。下单前校验商城是否欠费到期。 */
	private LocalDateTime expireTime;

}
