package com.zhiflow.arch;

import java.util.Map;

/**
 * 物理删实体登记处 —— 软删策略的单一控制点。
 *
 * <p><b>背景。</b>{@code BaseEntity.delFlag} 上加了 {@code @TableLogic} 之后，MyBatis-Plus 把
 * 全部 235 个实体的 {@code removeById}/{@code deleteById} 改写成 {@code UPDATE del_flag='1'}，
 * 而且<b>没有「按实体关闭」的开关</b>（注解在基类字段上，子类无法覆盖）。
 * 所以「这个实体到底是软删还是物理删」不能靠读调用点推断，只能有一处显式登记 —— 就是这里。
 *
 * <p><b>登记 ≠ 豁免既有违例。</b>与 {@link ArchExemptions} 的区别：
 * <ul>
 *   <li>{@code ArchExemptions}：规则不该管那个<b>位置</b>（如 Feign 内部端点不该要求鉴权）；</li>
 *   <li>本类：规则管得对，但这个<b>实体</b>的正确答案就是物理删，且理由必须写出来。</li>
 * </ul>
 * 存量技术债一律走 baseline，不要往这里塞 —— 塞进来等于永久放弃软删。
 *
 * <p><b>加一条的前提</b>：能说清「为什么软删会坏」。两条现存登记都是
 * 「删掉再按同一自然键重建」，软删的墓碑行会占着唯一键让重建直接 duplicate key ——
 * 这一点由 {@code S8SoftDeleteSpikeTest.Q3a} 在真 PG 上实测坐实，不是推测。
 *
 * <p>登记后，该实体的删除必须走 Mapper 裸 SQL（方法名以 {@code physicalRemove} 开头）：
 * {@code @TableLogic} 对自定义 SQL 不生效。
 */
final class PhysicalDeleteRegistry {

	/** 实体简单名 → 为什么这个实体不能软删 */
	static final Map<String, String> ENTITIES = Map.of(
			"ShoppingCart",
			"加购合并「删旧行 → 立刻插同 (tenant,user,spu,sku) 新行」，墓碑行占着唯一索引 "
					+ "shopping_cart_tenant_id_user_id_spu_id_sku_id_idx，软删会让加购全线 duplicate key；"
					+ "且购物车无回收站语义，墓碑只会让表无限膨胀",

			"WxApp",
			"主键是微信 appId（IdType.INPUT），重新授权流程是「删旧行 → 立刻按同 appId 重插」"
					+ "（WxOpenAuthController.jump、WxOpenNotifyController 取消授权/快速注册回调）。"
					+ "墓碑占主键，saveOrUpdate 的 getById 被 @TableLogic 过滤后看不见它 → 走 insert → "
					+ "主键冲突 500，重试永远走同一条路，授权通道永久卡死。"
					+ "主键做不成部分唯一索引，V80 对这一类没有保护");

	private PhysicalDeleteRegistry() {
	}

	static boolean isPhysicalDelete(String entitySimpleName) {
		return ENTITIES.containsKey(entitySimpleName);
	}
}
