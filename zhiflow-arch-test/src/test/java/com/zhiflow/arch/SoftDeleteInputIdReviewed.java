package com.zhiflow.arch;

import java.util.Map;

/**
 * 「主键即业务键（{@code IdType.INPUT}）且走软删」的复核登记处。
 *
 * <p><b>为什么单独有这张表。</b>同一类 bug 在 S8 里出现了四次，形态完全一样：
 * 实体的主键不是自增/雪花而是业务键（appId、user_id、域名……），代码里存在
 * 「删掉 → 立刻按同一主键重建」的序列。软删之后墓碑行占着主键，而重建路径的存在性检查
 * （{@code getById} / {@code exists} / {@code saveOrUpdate}）被 {@code @TableLogic} 追加
 * {@code del_flag='0'} 后<b>看不见墓碑</b>，于是判定「不存在」→ 裸 insert → 主键冲突 500，
 * 且每次重试都走同一条路，业务通道永久卡死。
 *
 * <p><b>主键做不成部分唯一索引</b>，所以 V80 那批 {@code WHERE del_flag='0'} 的部分索引
 * 对这一类<b>完全没有保护</b>——这正是它比普通唯一键冲突更危险的地方。
 *
 * <p>因此规则要求：凡 {@code IdType.INPUT} 且继承 {@code BaseEntity} 的实体，二选一：
 * <ul>
 *   <li>登记进 {@link PhysicalDeleteRegistry}（走物理删，墓碑压根不存在），或</li>
 *   <li>登记进本表，并写清<b>为什么它软删后不会走「删 + 同主键重插」</b>。</li>
 * </ul>
 *
 * <p><b>登记的门槛是「查过删除与插入两侧的全部调用点」，不是「看着不像」。</b>
 * 下面每条理由都对应一次实际的调用点排查（Java 调用点 + XML {@code <delete>} 都查）。
 * 排查不出结论就不要登记 —— 让门禁红着，比编一条理由让它绿着安全得多。
 */
final class SoftDeleteInputIdReviewed {

	/** 实体简单名 → 为什么该实体软删后不会撞「删 + 同主键重插」 */
	static final Map<String, String> ENTITIES = Map.ofEntries(
			Map.entry("ChainLevelCfg",
				"全仓零删除调用点（Java 的 removeById/removeByIds/removeBatchByIds/deleteById 与 XML 的 "
						+ "<delete> 都已排查），墓碑不可能产生"),

			Map.entry("ChainWithdraw",
				"全仓零删除调用点，同上。提现单是账本，只改状态不删"),

			Map.entry("DoctorSecurity",
				"全仓零删除调用点，同上。TOTP 绑定只做启用/停用，不删行"),

			Map.entry("ShopDeposit",
				"全仓零删除调用点，同上。保证金是账本，只改状态不删"),

			Map.entry("UserBalance",
				"全仓零删除调用点，同上。余额账户只增减金额不删行"),

			Map.entry("UserPoints",
				"全仓零删除调用点，同上。积分账户只增减不删行"),

			Map.entry("SysTenant",
				"删除入口只有 SysTenantController:142，但两条创建路径都是现生成雪花 id —— "
						+ "SysTenantController.save 的 IdUtil.getSnowflake(1,2).nextId() 与 "
						+ "SysTenantApplyServiceImpl.verify 同款。删掉的租户 id 永不复用，构不成同主键重插"),

			Map.entry("SysTenantPackageMenu",
				"主键 packageId 来自 SysTenantPackage.id，而后者是 IdType.ASSIGN_ID（雪花）。"
						+ "删除只发生在 SysTenantPackageServiceImpl.removeById 删整个套餐时级联，"
						+ "新建套餐必然是新 id；菜单变更走 updateById（SysTenantPackageMenuController:62）"
						+ "而非删+插。故不存在同主键重插"),

			// 资讯站四张内容表（2026-09-29 排查：mall 各模块全部 Java 调用点 + 全部 mapper XML + Zx*Mapper 上的注解 SQL）。
			// 应用里没有任何 insert / save / 物理删：C 端 ZxContentServiceImpl 只有浏览量的 update(null, wrapper)，
			// 后台 ZxContentAdminController 只有 updateById；mapper XML 不引用这四张表，Zx*Mapper 上没有 @Insert / @Delete。
			// 唯一的插入来源是离线工具 tools/zx-content-import/load.sh：每张表先 TRUNCATE 再整表 copy 重灌，
			// 墓碑随 TRUNCATE 清空，不会撞主键；load_release_time.sh 只 UPDATE zx_drug。
			// ⚠️ 代价：重跑 load.sh 会同时冲掉后台做过的删除 / 编辑 / 排序与累计浏览量，重跑前先评估。
			Map.entry("ZxArticle",
				"应用内零插入；删除只有 ZxContentAdminController.removeArticle 置 del_flag。"
						+ "唯一插入来源 tools/zx-content-import/load.sh 先 TRUNCATE 再整表灌库，墓碑随之清空，不会同主键重插冲突"),
			Map.entry("ZxDrug",
				"应用内零插入；删除只有 ZxContentAdminController.removeDrug 置 del_flag。"
						+ "插入只来自 load.sh（先 TRUNCATE），load_release_time.sh 只 UPDATE，不会同主键重插冲突"),
			Map.entry("ZxDepartment",
				"全仓零删除调用点（后台只有 updateDepartment），插入只来自 load.sh 先 TRUNCATE 再整表灌库，不会产生墓碑"),
			Map.entry("ZxDisease",
				"全仓零删除调用点（后台只有 updateDisease），插入只来自 load.sh 先 TRUNCATE 再整表灌库，不会产生墓碑"),
			// 2026-10-07 排查：ZxContentServiceImpl 与 ZxContentAdminController 对字典只有 selectList，
			// 无 insert / save / update / delete；mapper XML 不引用该表，两个 ZxArticleCategory*Mapper 上无注解 SQL。
			Map.entry("ZxArticleCategory",
				"全仓零删除、零插入调用点：应用只读字典（C 端分类接口与后台下拉都是 selectList），"
						+ "四行字典由 V170 迁移 INSERT ... ON CONFLICT DO NOTHING 种入，不存在删后同主键重插"));

	// 本表建立时排查出的唯一一个真实「删 + 同主键重插」组合是 UserRecord
	// （删：UserRecordController；插：OrderInfoServiceImpl 支付成功后 getById 为 null 就裸 save）。
	// 它没有被编一条理由塞进这张表，而是按 C1 那三个同形态实体的先例改成了物理删，
	// 现登记在 PhysicalDeleteRegistry。这正是本表期望的用法：查出真问题就去改代码，
	// 而不是补一条说辞让门禁变绿。

	private SoftDeleteInputIdReviewed() {
	}

	static boolean isReviewed(String entitySimpleName) {
		return ENTITIES.containsKey(entitySimpleName);
	}
}
