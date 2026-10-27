package com.zhiflow.mall.common.trade.item;

/**
 * 商品项状态的 SQL，mall-api / mall-admin 两个 mapper 共用同一份（注解里直接引用常量）。
 *
 */
public final class ItemStatusSql {

	private ItemStatusSql() {
	}

	/** 发货痕迹（别名 i）。 */
	public static final String SHIPPED_TRACE = "(i.shipped_time IS NOT NULL OR i.delivery_time IS NOT NULL OR i.delivery_type IS NOT NULL)";

	/** 商品项当前状态（别名 i）：有 item_status 用它，空值按发货痕迹推导（与 {@link ItemStatus#of} 一致）。 */
	public static final String DERIVED = "COALESCE(i.item_status, CASE"
			+ " WHEN " + SHIPPED_TRACE + " THEN 'SHIPPED'"
			+ " ELSE 'NORMAL' END)";

	/**
	 * 发货：列出的项记发货时刻（已有的不覆盖）；当前为 NORMAL 的改 SHIPPED，已取消的不动。参数 orderId、itemIds。
	 */
	public static final String MARK_SHIPPED = "<script>UPDATE order_item i SET"
			+ " item_status = CASE WHEN " + DERIVED + " = 'NORMAL' THEN 'SHIPPED' ELSE " + DERIVED + " END,"
			+ " shipped_time = COALESCE(i.shipped_time, now())"
			+ " WHERE i.order_id = #{orderId} AND i.del_flag = '0' AND i.id IN"
			+ " <foreach collection='itemIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>";

	/** 订单取消（O5）：全部项 CANCELLED。参数 orderId。 */
	public static final String MARK_CANCELLED = "UPDATE order_item SET item_status = 'CANCELLED'"
			+ " WHERE order_id = #{orderId} AND del_flag = '0' AND item_status IS DISTINCT FROM 'CANCELLED'";
}
