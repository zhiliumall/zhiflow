package com.zhiflow.mall.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.common.entity.PayException;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 差错池（支付与订单状态机 v1.2 第六章）。表在租户 schema，靠 search_path 切换。
 *
 * <p>mall-api 与 mall-admin 各有一份同形的本接口：mall-common 没有 mapper 扫描能力，
 * 而 mapper 必须落在各服务自己的 mapper 包里才进得了数据源路由表（{@code DataSourceRoutingCoverageTest}）。
 * 两份 SQL 必须保持一致。
 */
public interface PayExceptionMapper extends BaseMapper<PayException> {

	/**
	 * 同一笔钱只入池一次：{@code dedupe_key} 唯一，冲突即跳过。
	 *
	 * <p>注解 SQL 不走 MyBatis-Plus 的 ASSIGN_ID，主键由调用方给（{@code PayExceptionRecorder}）。
	 *
	 * @return 1 = 新入池；0 = 已存在
	 */
	@Insert("""
			INSERT INTO pay_exception (id, type, status, order_id, order_no, pay_order_id,
			                           channel, channel_txn_id, amount_minor, currency, callback_log_id,
			                           dedupe_key, detail, tenant_id, create_time, update_time)
			VALUES (#{e.id}, #{e.type}, #{e.status}, #{e.orderId}, #{e.orderNo}, #{e.payOrderId},
			        #{e.channel}, #{e.channelTxnId}, #{e.amountMinor}, #{e.currency}, #{e.callbackLogId},
			        #{e.dedupeKey}, #{e.detail}, #{e.tenantId}, now(), now())
			ON CONFLICT (dedupe_key) DO NOTHING
			""")
	int insertIfAbsent(@Param("e") PayException e);

	// ─────────── 差错池最小版（2026-09-26）：只有「标记已处理」；与 mall-api 那份同名 SQL 保持一致 ───────────

	/** 人工标记：OPEN → RESOLVED（条件更新，已被处理的返回 0）。 */
	@Update("""
			UPDATE pay_exception
			   SET status = #{status}, resolution = #{resolution}, resolved_by = #{by}, resolve_reason = #{reason},
			       resolved_time = now(), update_time = now()
			 WHERE id = #{id} AND status = 'OPEN'
			""")
	int finish(@Param("id") String id, @Param("status") String status, @Param("resolution") String resolution,
			@Param("by") String by, @Param("reason") String reason);
}
