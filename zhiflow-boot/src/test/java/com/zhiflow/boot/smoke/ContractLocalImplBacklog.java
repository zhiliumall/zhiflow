package com.zhiflow.boot.smoke;

import java.util.Map;

/**
 * 尚无本地实现的契约接口待办表（key = 契约接口全限定名，value = 卡在哪）。
 *
 * <p>{@code AllAssemblySmokeTest#everyContractIsLocallyImplementedOrRegisteredAsBacklog} 从契约接口<b>全集</b>出发，
 * 没有本地实现的必须在这里登记理由。<b>补齐一个就删一行</b>，死条目守卫会拦住「实现补了、表没删」。
 */
final class ContractLocalImplBacklog {

	// 2026-08-16 backlog 曾清零：最后一项 ZhiflowPayApi 的本地实现
	// （zhiflow-pay-admin/.../local/ZhiflowPayLocalApi.java）已就位。
	//
	// ⚠️ 2026-09-13（B1 期间）重新发现 4 个缺口，**不是 B1 造成的**：
	// 它们是 09-04 ~ 09-08「AI 导购 Agent」与「退款链路」两批上线时加的契约，
	// 加的时候没做本地实现，也没登记进本表 —— 而本表与 AllAssemblySmokeTest 自 08-16 起
	// 一行未动，也就是说这条反向门禁从 09-04 起一直是红的，只是期间没人跑单体冒烟。
	//
	// 登记在此是为了让债**可见**（补齐一个删一行，死条目守卫会拦住"实现补了表没删"），
	// 不是认可现状：单体 all 形态下这 4 条契约的每一次调用都是网络异常。
	// 2026-10-20 开源分支：四条全部随功能删除（AI 导购的订单查询 / 推荐埋点 / 退款资格三个契约，
	// 以及只剩退款方法、零调用方的 PaymentInsideApi），backlog 再次清零。
	static final Map<String, String> PENDING = Map.of();

	private ContractLocalImplBacklog() {
	}

}
