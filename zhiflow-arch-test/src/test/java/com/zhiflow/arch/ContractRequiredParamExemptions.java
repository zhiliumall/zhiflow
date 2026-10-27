package com.zhiflow.arch;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code required=true} 的 {@code @RequestParam} 在契约本地实现里<b>没有</b>空值守卫的显式豁免表。
 *
 * <h2>背景：为什么必须有守卫</h2>
 * <p>spring-web 的 HTTP interface 客户端对 {@code required=true} 的 {@code @RequestParam}
 * 拿到 null 时，在<b>参数解析阶段</b>就抛 {@code IllegalArgumentException} ——
 * <b>请求根本没有发出</b>（2026-08-16 用 spring-web 6.2.19 实证，见
 * 支付本地实现审计）。
 * 本地实现若把 null 直接透传给 controller，就会让「多进程形态下从来不会发生的调用」
 * 在单体形态下真的发生 —— 那不是恒等变换。{@code queryOrder} 上这件事恰好落在
 * <b>补账与取消闸门</b>两条钱的路径上。
 *
 * <h2>登记规则</h2>
 * <ul>
 * <li>键：{@code 契约接口简名.方法名(参数名)}；值：<b>豁免理由 + 判据</b>。</li>
 * <li>理由必须是<b>可复核的事实</b>（调用点实参是什么、有没有可能为 null），
 *     不接受「应该不会为 null」这种断言。</li>
 * <li>基本类型（{@code int}/{@code long}/…）由门禁<b>自动</b>跳过，不必登记 ——
 *     它们语言层面就不可能是 null。</li>
 * <li>补了守卫就<b>删掉对应行</b>；门禁有反向断言盯着死条目（见
 *     {@code ContractRequiredParamGuardGateTest#exemptionsHaveNoStaleEntries}）。</li>
 * </ul>
 *
 * <h2>2026-08-16 全仓盘点结论（审查 P3-3）</h2>
 * <p>52 个契约接口、163 个 required 参数（123 个 {@code @RequestHeader} + 40 个
 * {@code @RequestParam}）。全仓<b>字面 null 实参只有 3 处</b>，全部是
 * {@code ZhiflowPayApi.queryOrder(orderId)}；其余契约的调用点实参逐条核过，
 */
final class ContractRequiredParamExemptions {

	private ContractRequiredParamExemptions() {
	}

	static final Map<String, String> ALLOWED = new LinkedHashMap<>();

	// 2026-10-20 开源分支：原有 11 条豁免所属的契约方法（足迹 / 加购事件 / 用户搜索 / 推荐 /
	// 第三方码登录 / 公众号客服消息）随功能一起删除，豁免表清空。新增豁免前先读本类头部的准入条件。

}
