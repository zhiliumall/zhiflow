package com.zhiflow.mall.api.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 库存预占流水（表在租户 schema）。状态只做条件更新：同一行只会被放出 / 确认一次，
 * 取消与兜底任务并发时只有一方拿到行、也只有那一方回补库存。
 */
@Mapper
public interface StockHoldMapper {

	@Insert("INSERT INTO stock_hold (id, order_id, order_item_id, sku_id, qty, status, expire_time, tenant_id, create_time, update_time) "
			+ "VALUES (#{id}, #{orderId}, #{orderItemId}, #{skuId}, #{qty}, 'HOLD', now() + (#{minutes} * interval '1 minute'), "
			+ "#{tenantId}, now(), now())")
	int insertHold(@Param("id") String id, @Param("orderId") String orderId, @Param("orderItemId") String orderItemId,
			@Param("skuId") String skuId, @Param("qty") int qty, @Param("minutes") long minutes, @Param("tenantId") String tenantId);

	/** HOLD → RELEASED，返回这次真的放出来的行（sku_id, qty）；已被放出 / 确认的行不在其中。 */
	@Select("UPDATE stock_hold SET status = 'RELEASED', release_reason = #{reason}, update_time = now() "
			+ "WHERE order_id = #{orderId} AND status = 'HOLD' RETURNING sku_id AS \"skuId\", qty")
	@Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
	List<Map<String, Object>> releaseHolds(@Param("orderId") String orderId, @Param("reason") String reason);

	@Update("UPDATE stock_hold SET status = 'CONFIRMED', update_time = now() WHERE order_id = #{orderId} AND status = 'HOLD'")
	int confirmHolds(@Param("orderId") String orderId);

	@Select("SELECT count(*) FROM stock_hold WHERE order_id = #{orderId}")
	long countHolds(@Param("orderId") String orderId);

	@Select("SELECT order_id FROM stock_hold WHERE status = 'HOLD' AND expire_time < now() GROUP BY order_id "
			+ "ORDER BY min(expire_time) LIMIT #{limit}")
	List<String> selectExpiredHoldOrders(@Param("limit") int limit);
}
