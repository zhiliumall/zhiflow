package com.zhiflow.mall.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.common.entity.OrderStateLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 订单状态流水（V118，只追加）。mall-api 与 mall-admin 各一份同形接口。 */
public interface OrderStateLogMapper extends BaseMapper<OrderStateLog> {

	/** 按原 operationId 查订单状态机流水，供命令 UNKNOWN 恢复使用；只读，不触发状态迁移。 */
	@Select("SELECT * FROM order_state_log WHERE order_id = #{orderId} AND request_id = #{operationId} "
			+ "ORDER BY create_time DESC, id DESC LIMIT 1")
	OrderStateLog selectByOrderAndOperation(@Param("orderId") String orderId,
			@Param("operationId") String operationId);
}
