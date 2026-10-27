package com.zhiflow.mall.admin.util;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.mall.common.entity.MaterialGroup;

/**
 * 后台「整实体入库」增改接口的入参规则，逐列照抄库表约束（2026-10-01 取自生产
 * {@code zhiflow_mall_1}，{@code information_schema.columns}：NOT NULL 且无默认值的列判必填，
 * varchar(n) 判长度）。见 {@link RequiredFieldGuard} 的「只拦库本来就会拒的」原则。
 *
 * <p>不在这里判的列：主键（{@code ASSIGN_ID}）、{@code tenant_id / del_flag / create_time / update_time}
 * （{@code ZhiflowMetaObjectHandler} 插入时填）、服务端自己写的列（如任务的 {@code task_status}）。
 *
 * <p>更新只判主键与长度：MyBatis-Plus {@code updateById} 跳过 {@code null} 字段，缺值不会撞 NOT NULL，
 * 只有超长会；要求更新也带齐必填项反而会打断「只改一个字段」的调用方（如后台改排序）。
 * 主键是例外：不带主键的修改什么也改不了，实体又全是 null 时 SET 子句为空、拼出非法 SQL 直接 500。
 */
public final class AdminEntityRules {

    private AdminEntityRules() {
    }

    // ---------------- 商品评价（goods_appraises） ----------------

    // ---------------- 评价回复模板（goods_appraises_template） ----------------

    // ---------------- 商品榜单规则（goods_trend_rule） ----------------

    // ---------------- 标签组（label_group） ----------------

    // ---------------- 素材分组（material_group） ----------------

    public static void checkInsert(MaterialGroup e) {
        materialGroupLengths(RequiredFieldGuard.of(e)
                .required("分组类型", "ofType", e.getOfType())
                .required("分组名称", "name", e.getName())
                .required("素材类型", "materialType", e.getMaterialType()), e)
                .check();
    }

    public static void checkUpdate(MaterialGroup e) {
        materialGroupLengths(RequiredFieldGuard.of(e).required("主键", "id", e.getId()), e).check();
    }

    private static RequiredFieldGuard materialGroupLengths(RequiredFieldGuard g, MaterialGroup e) {
        return g.maxLength("分组类型", "ofType", e.getOfType(), 2)
                .maxLength("分组名称", "name", e.getName(), 200)
                .maxLength("素材类型", "materialType", e.getMaterialType(), 2)
                .maxLength("店铺", "shopId", e.getShopId(), 32);
    }

    // ---------------- 我的组件（page_component_template） ----------------

    // ---------------- 秒杀会场（seckill_hall） ----------------

    // ---------------- 任务奖励（task） ----------------

    // ---------------- 店铺用户（user_shop） ----------------

    // ---------------- 会员类型（vip_type） ----------------

    // ---------------- 店铺商品分类（goods_category_shop） ----------------

    // ---------------- 运费模板（freight_templat） ----------------

    // ---------------- 限时活动（flash_sale_activity） ----------------
}
