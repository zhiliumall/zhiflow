/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.zhiflow.mall.common.entity.OrderInfo;
import org.apache.ibatis.annotations.Param;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 商城订单
 *
 * @date 2019-09-10 15:21:22
 */
public interface OrderInfoMapper extends BaseMapper<OrderInfo> {

    IPage<OrderInfo> selectPage2(IPage<OrderInfo> page, @Param("query") OrderInfo orderInfo);

	OrderInfo selectById2(Serializable id);

	default Long countByUserIdAndStatus(String userId, String status, String consumptionCondition) {
		return this.selectCount(
				new LambdaQueryWrapper<OrderInfo>()
						.eq(OrderInfo::getUserId, userId)
						.eq(consumptionCondition.equals("1"),OrderInfo::getStatus, status)
						.eq(OrderInfo::getIsPay, "1")
		);
	}

	default BigDecimal sumByUserIdAndStatus(String userId, String status, String consumptionCondition) {
		return sumByUserIdAndStatus(userId, status, consumptionCondition, null,null);
	}

	default BigDecimal sumByUserIdAndStatus(String userId, String status, String consumptionCondition, LocalDateTime startTime, LocalDateTime endTime) {
		return this.selectList(
						new LambdaQueryWrapper<OrderInfo>()
								.eq(OrderInfo::getUserId, userId)
								.eq(consumptionCondition.equals("1"), OrderInfo::getStatus, status)
								.eq(OrderInfo::getIsPay, "1")
								.ge(Objects.nonNull(startTime), OrderInfo::getCreateTime, startTime)
								.le(Objects.nonNull(endTime), OrderInfo::getCreateTime, endTime)
				).stream()
				.reduce(BigDecimal.ZERO, (a, b) -> a.add(b.getPaymentPrice()), BigDecimal::add);
	}

    default List<OrderInfo> selectByUserIdAndStatus(String userId, String status, String consumptionCondition) {
		return this.selectList(
				new LambdaQueryWrapper<OrderInfo>()
						.eq(OrderInfo::getUserId, userId)
						.eq(consumptionCondition.equals("1"),OrderInfo::getStatus, status)
						.eq(OrderInfo::getIsPay, "1")
		);
    }

    List<Map> getList();

	/**
	 * 迁移用户订单数据（用于账号合并）
	 * @param oldUserId 原用户ID
	 * @param newUserId 新用户ID
	 * @return 更新记录数
	 */
	@org.apache.ibatis.annotations.Update("UPDATE order_info SET user_id = #{newUserId} WHERE user_id = #{oldUserId}")
	int updateUserId(@Param("oldUserId") String oldUserId, @Param("newUserId") String newUserId);
}
