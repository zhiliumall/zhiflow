package com.zhiflow.mall.admin.mapper;

import com.zhiflow.mall.common.entity.OrderInfo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 订单状态机专用 SQL。只被 {@code OrderStatePortImpl} 调用。
 *
 * <p>mall-api 与 mall-admin 各有一份同形的本接口（mall-common 没有 mapper 扫描能力），两份 SQL 必须保持一致。
 * 注解 SQL 不走 MyBatis-Plus 的逻辑删除，所以显式带 {@code del_flag = '0'}。
 */
public interface OrderStateMachineMapper {

	/** 锁内重读（行锁）。 */
	@Select("SELECT * FROM order_info WHERE id = #{id} AND del_flag = '0' FOR UPDATE")
	OrderInfo selectForUpdate(@Param("id") String id);

	/**
	 * PG 触发器通行证（本事务内有效）。状态机只在自己那一条条件 UPDATE 前后开关，
	 * 让通行证只覆盖这一条语句（7.2 第三层）。
	 */
	@Select("SELECT set_config('app.state_machine', #{value}, true)")
	String setStateMachinePass(@Param("value") String value);

	/**
	 * 条件更新：只有 (status, is_pay) 仍是锁内读到的原值才改；伴随列非空才写。
	 * {@code IS NOT DISTINCT FROM} 让「待付款 = status 为空」也能做等值比较。
	 */
	@Update("""
			<script>
			UPDATE order_info
			   SET status = #{toStatus,jdbcType=VARCHAR},
			       is_pay = #{toIsPay,jdbcType=VARCHAR},
			       update_time = now(),
			       state_since = CASE WHEN status IS NOT DISTINCT FROM #{toStatus,jdbcType=VARCHAR}
			                           AND is_pay IS NOT DISTINCT FROM #{toIsPay,jdbcType=VARCHAR}
			                          THEN state_since ELSE now() END
			       <if test="paymentTime != null">, payment_time = #{paymentTime}</if>
			       <if test="transactionId != null">, transaction_id = #{transactionId}</if>
			       <if test="paymentType != null">, payment_type = #{paymentType}</if>
			       <if test="tradeType != null">, trade_type = #{tradeType}</if>
			       <if test="cancelTime != null">, cancel_time = #{cancelTime}</if>
			       <if test="receiverTime != null">, receiver_time = #{receiverTime}</if>
			       <if test="deliveryTime != null">, delivery_time = #{deliveryTime}</if>
			 WHERE id = #{id}
			   AND del_flag = '0'
			   AND status IS NOT DISTINCT FROM #{expectedStatus,jdbcType=VARCHAR}
			   AND is_pay IS NOT DISTINCT FROM #{expectedIsPay,jdbcType=VARCHAR}
			</script>
			""")
	int compareAndSet(@Param("id") String id,
			@Param("expectedStatus") String expectedStatus, @Param("expectedIsPay") String expectedIsPay,
			@Param("toStatus") String toStatus, @Param("toIsPay") String toIsPay,
			@Param("paymentTime") LocalDateTime paymentTime, @Param("transactionId") String transactionId,
			@Param("paymentType") String paymentType, @Param("tradeType") String tradeType,
			@Param("cancelTime") LocalDateTime cancelTime, @Param("receiverTime") LocalDateTime receiverTime,
			@Param("deliveryTime") OffsetDateTime deliveryTime);
}
