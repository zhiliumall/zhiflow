/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.common.entity.OrderItem;
import org.apache.ibatis.annotations.Param;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * 商城订单详情
 *
 * @date 2019-09-10 15:31:40
 */
public interface OrderItemMapper extends BaseMapper<OrderItem> {

	List<OrderItem> selectList2(OrderItem orderItem);

	OrderItem selectById2(Serializable id);
	List<OrderItem> listOrder(String orderId);

	/**
	 * perf 2026-09-15：批量按订单 id 取订单项，替代 orderInfoMap2/distributionOrderMap1
	 * 里逐行的 selectList2/listOrder 嵌套 select（N+1）。列集合与 orderItemSql 一致，
	 * 与两处原嵌套 select 产出的字段完全相同。
	 */
	List<OrderItem> selectListByOrderIds(@Param("orderIds") Collection<String> orderIds);

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
