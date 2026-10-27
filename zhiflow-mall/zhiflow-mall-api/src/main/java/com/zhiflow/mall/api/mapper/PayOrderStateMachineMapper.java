package com.zhiflow.mall.api.mapper;

import com.zhiflow.mall.common.entity.PayOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 支付单状态机专用 SQL（支付与订单状态机 v1.2 三章 / 5.1 / 7.2）。只被 {@code PayOrderStatePortImpl} 调用：
 * pay_order 的 status 与退款额度列只有这里写。
 *
 * <p>注解 SQL 不走 MyBatis-Plus 的逻辑删除，显式带 {@code del_flag = '0'}；表在租户 schema（search_path 切换）。
 */
public interface PayOrderStateMachineMapper {

	/** 锁内重读（行锁）。 */
	@Select("SELECT * FROM pay_order WHERE pay_no = #{payNo} AND del_flag = '0' FOR UPDATE")
	PayOrder selectForUpdate(@Param("payNo") String payNo);

	/** PG 触发器通行证（本事务内有效），只在下面那条条件 UPDATE 前后开关。 */
	@Select("SELECT set_config('app.state_machine', #{value}, true)")
	String setStateMachinePass(@Param("value") String value);

	/**
	 * 条件更新：status 仍是锁内读到的原值才改；伴随列非空才写。
	 */
	@Update("""
			<script>
			UPDATE pay_order
			   SET status = #{toStatus},
			       update_time = now(),
			       state_since = CASE WHEN status IS NOT DISTINCT FROM #{toStatus} THEN state_since ELSE now() END
			       <if test="channelTxnId != null">, channel_txn_id = #{channelTxnId}</if>
			       <if test="payTime != null">, pay_time = #{payTime}</if>
			       <if test="latePaid != null">, late_paid = #{latePaid}</if>
			       <if test="duplicatePaid != null">, duplicate_paid = #{duplicatePaid}</if>
			       <if test="failReason != null">, fail_reason = #{failReason}</if>
			       <if test="channelClosed != null">, channel_closed = #{channelClosed}</if>
			       <if test="closedTime != null">, closed_time = #{closedTime}</if>
			 WHERE pay_no = #{payNo}
			   AND del_flag = '0'
			   AND status = #{expectedStatus}
			</script>
			""")
	int compareAndSet(@Param("payNo") String payNo, @Param("expectedStatus") String expectedStatus,
			@Param("toStatus") String toStatus, @Param("channelTxnId") String channelTxnId,
			@Param("payTime") LocalDateTime payTime, @Param("latePaid") Boolean latePaid,
			@Param("duplicatePaid") Boolean duplicatePaid, @Param("failReason") String failReason,
			@Param("channelClosed") Boolean channelClosed, @Param("closedTime") OffsetDateTime closedTime);

	/**
	 * P6 的可选动作做成之后打「渠道已关单」标记（附加标记）。<b>不改 status</b>：
	 * 只对仍是 CANCELLED 的单生效，打上后补单任务不再把它列入「已关仍可付」扫描。
	 */
	@Update("""
			UPDATE pay_order
			   SET channel_closed = true,
			       update_time = now()
			 WHERE pay_no = #{payNo}
			   AND del_flag = '0'
			   AND status = 'CANCELLED'
			   AND channel_closed IS NOT TRUE
			""")
	int markChannelClosed(@Param("payNo") String payNo);
}
