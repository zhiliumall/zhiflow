package com.zhiflow.upms.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * {@code AgreementConfigApi#getByType} 的投影 —— 取代把 {@code AgreementConfig} 实体当契约。
 *
 * <h2>为什么带的是「实体自己声明的字段」而不是更窄的交集</h2>
 * <p>3 个调用点里有 <b>1 个是纯透传</b>：mall-api 的 {@code AgreementConfigController}
 * 把整条响应交给前端（{@code mustLogin=false} 的公开端点）。H5 实测只读 {@code agreementContent}
 * （{@code public/agreement/index.vue}），但 PC / App 两个仓库不在本仓、核不到。
 * 对这类端点，收窄到「我能看见的调用方读了什么」会把风险换成**静默的功能丢失**
 * （前端拿不到字段不会报错，只会少显示一块）。
 * 所以口径是：带实体<b>自己声明</b>的全部字段，只丢掉 {@code TenantEntity} 的基础设施字段
 * （{@code tenantId} / {@code delFlag}）—— 那两个是库内隔离与逻辑删除，前端拿了也没用。
 *
 * <p>另 2 个调用点（mall-api {@code DistributionContractSignServiceImpl}，推广合同签约）
 * 读 {@code agreementContent} / {@code agreementName} / {@code updateTime}（版本号），
 * {@code updateTime} 取不到时回退 {@code id} —— 所以这两个字段必须在。
 */
@Data
public class AgreementInfo implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private String id;

	/** 协议类型：1 会员卡协议；2 租户充值协议。 */
	private String agreementType;

	private String agreementName;

	private String agreementContent;

	private LocalDateTime createTime;

	/** 推广合同签约的版本号来源（为空时回退 {@link #id}）。 */
	private LocalDateTime updateTime;
}
