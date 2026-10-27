/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.common.entity.PayOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;


/**
 * 支付单
 *
 * <p>表落在**租户 schema**，靠 {@code SET search_path} 切换，所以 SQL 里表名不带 schema 前缀。
 *
 * <p>支付状态机 v1.2 阶段 1：本接口不写状态。status 与退款额度列只由 {@code PayOrderStateMachineMapper} 写
 * （原 markPaidIfNotYet / addRefundedAmount / releaseRefundedAmount / compareAndSetStatus 已移除——
 * 它们在退款提交时就改状态、失败时把 REFUNDED 回退成 PAID，均为明令禁止的跳转）。
 *
 * <p>⚠️ 本类的注解 SQL <b>不走 MyBatis-Plus 的逻辑删除</b>（{@code @TableLogic} 只作用于
 * MP 自动生成的语句），所以每条手写 SQL 都显式带了 {@code del_flag = '0'}。
 *
 * @date 2026-09-07
 */
public interface PayOrderMapper extends BaseMapper<PayOrder> {
    @Select("SELECT * FROM pay_order WHERE pay_no = #{payNo} AND del_flag = '0'")
    PayOrder selectByPayNo(@Param("payNo") String payNo);

    /**
     * 补单任务候选：PAYING 超过 {@code olderThan} 的；本地已关（CANCELLED）但渠道没关单、
     * 渠道仍可能扣款的（{@code channel_closed = false}）。只看 {@code since} 之后建的单（渠道最大可付窗口）。
     */
    @Select("""
            SELECT * FROM pay_order
             WHERE del_flag = '0'
               AND create_time >= #{since}
               AND ((status = 'PAYING' AND create_time <= #{olderThan})
                    OR (status = 'CANCELLED' AND channel_closed = false))
             ORDER BY create_time
             LIMIT #{limit}
            """)
    java.util.List<PayOrder> selectReconcileCandidates(@Param("since") java.time.LocalDateTime since,
                                                        @Param("olderThan") java.time.LocalDateTime olderThan,
                                                        @Param("limit") int limit);

}
