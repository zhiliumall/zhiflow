/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.common.entity.OrderItem;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 商城订单详情
 *
 * @date 2019-09-10 15:31:40
 */
public interface OrderItemMapper extends BaseMapper<OrderItem> {

	List<OrderItem> selectList2(OrderItem orderItem);

	/**
	 * selectList2 的批量版：按多个 orderId 一次性查回订单项，供订单列表两段式装配。
	 * SQL 与 selectList2 逐字一致，仅将 order_id = 改为 order_id IN。
	 */
	List<OrderItem> selectListByOrderIds(@Param("orderIds") Collection<String> orderIds);

	OrderItem selectById2(Serializable id);

	List<OrderItem> listOrder(String orderId);

	@Select({
			"SELECT COALESCE(SUM(oi.quantity), 0)",
			"FROM order_item oi",
			"JOIN order_info o ON o.id = oi.order_id",
			"WHERE o.user_id = #{userId}",
			"  AND o.is_pay = '1'",
			"  AND o.del_flag = '0'",
			"  AND oi.del_flag = '0'",
			"  AND o.create_time >= #{startTime}",
			"  AND o.create_time <= #{endTime}"
	})
	Long countUserPurchaseQuantityAll(@Param("userId") String userId,
									 @Param("startTime") LocalDateTime startTime,
									 @Param("endTime") LocalDateTime endTime);

	/**
	 * 支付成功时写商品项货款实付（= 实付 − 本项运费，不小于 0），只写一次（{@code paid_amount IS NULL}）。
	 * 与 {@code RefundCaps#paidAmountOf} 同一口径。
	 */
	@org.apache.ibatis.annotations.Update("UPDATE order_item SET paid_amount = GREATEST(COALESCE(payment_price, 0) - COALESCE(freight_price, 0), 0) "
			+ "WHERE order_id = #{orderId} AND paid_amount IS NULL AND del_flag = '0'")
	int fillPaidAmount(@org.apache.ibatis.annotations.Param("orderId") String orderId);

	/** 发货——列出的项记发货时刻，NORMAL 的改 SHIPPED（见 ItemStatusSql）。 */
	@org.apache.ibatis.annotations.Update(com.zhiflow.mall.common.trade.item.ItemStatusSql.MARK_SHIPPED)
	int markShipped(@Param("orderId") String orderId, @Param("itemIds") Collection<String> itemIds);

	/** 订单取消（O5）后全部项 CANCELLED。 */
	@org.apache.ibatis.annotations.Update(com.zhiflow.mall.common.trade.item.ItemStatusSql.MARK_CANCELLED)
	int markCancelled(@Param("orderId") String orderId);
}
