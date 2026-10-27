package com.zhiflow.arch;

import java.util.Map;

/**
 * 「有 {@code del_flag} 列但不继承 {@code BaseEntity}」的实体登记处 —— 翻转射程之外。
 *
 * <p><b>存在理由。</b>S8 的软删翻转是在 {@code BaseEntity.delFlag} 上加 {@code @TableLogic}，
 * 所以只覆盖它的子类。但仓里还有一批实体<b>自己声明了 {@code delFlag} 字段、直接
 * {@code implements Serializable}</b>，它们有 {@code del_flag} 列、看起来像软删，
 * 实际上翻转对它们<b>完全不生效</b>，删除仍是物理删。
 *
 * <p>危险的不是「它们是物理删」，而是<b>「没人知道它们是物理删」</b>——
 * 「全量式翻转」这个说法会让人默认所有带 {@code del_flag} 的实体都已软删，
 * 于是把 {@code CouponUser}（电子券记录）这种当成有回收站的东西来用。
 *
 * <p>因此规则要求：凡带 {@code delFlag} 字段且带 {@code @TableName} 的类，
 * 若不继承 {@code BaseEntity}，必须二选一：自带 {@code @TableLogic}，或登记进本表并写明现状。
 * 目的只有一个 —— <b>不让任何实体处于「没人决定过它的删除语义」的状态</b>。
 *
 * <p>登记 ≠ 认可。下面几条里 {@code CouponUser} / {@code InvitationUserLog} 属权益/资金数据，
 * 物理删是否合适应当复核；本表先把现状固定下来，避免它继续隐身。
 */
final class OutsideFlipEntities {

	/** 实体简单名 → 现状说明（为什么它在翻转射程外，以及这意味着什么） */
	static final Map<String, String> ENTITIES = Map.ofEntries(
			Map.entry("LiveRoom",
					"直播间。不继承 BaseEntity，翻转不生效，删除仍是物理删"),
			Map.entry("LiveConfig",
					"直播配置。同上，物理删"),
			Map.entry("LiveRoomAppointment",
					"直播预约。同上，物理删"),
			Map.entry("CouponUser",
					"电子券用户记录。同上，物理删。⚠️ 属用户权益数据，删掉即无法追溯发放/核销，"
							+ "是否该改软删需产品复核 —— 本条只记录现状不代表认可"),
			Map.entry("GoodsAppraisesTags",
					"商品评价标签。同上，物理删"),
			Map.entry("InvitationUserLog",
					"邀请记录。同上，物理删。⚠️ V72 刚给它加过首单幂等列，"
							+ "物理删会把幂等依据一起删掉，是否该改软删需复核"),
			Map.entry("GlobalHotWord",
					"全局热词。同上，物理删。该表的 upsert 走 ON CONFLICT (word) WHERE del_flag='0'，"
							+ "谓词是波次 1 为兼容部分索引加的，与本条不冲突"),
			Map.entry("SysErrorLog",
					"错误日志。同上，物理删。日志类数据无回收站语义，物理删是对的"),
			Map.entry("GenTable",
					"代码生成器的表元数据。同上，物理删。开发期工具数据，无业务含义"),
			Map.entry("SysDatasource",
					"代码生成器的数据源配置。同上，物理删。同为开发期工具数据"),
			Map.entry("SlaTodo",
					"时效待办。不继承 BaseEntity。代码里没有删除路径："
							+ "生命周期靠 status（OPEN → DONE / AUTO_CLOSED），del_flag 只是与各业务表同形的预留列，"
							+ "唯一键 (biz_type, biz_id, state, state_since) 也不含它——真要清理只能物理删"));

	private OutsideFlipEntities() {
	}

	static boolean isRegistered(String entitySimpleName) {
		return ENTITIES.containsKey(entitySimpleName);
	}
}
