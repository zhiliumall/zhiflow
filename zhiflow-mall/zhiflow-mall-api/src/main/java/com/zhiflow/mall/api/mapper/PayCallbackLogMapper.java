/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.common.entity.PayCallbackLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;

/**
 * 支付回调留痕
 *
 * @date 2026-09-07
 */
public interface PayCallbackLogMapper extends BaseMapper<PayCallbackLog> {

    /**
     * 幂等落痕：同一渠道同一幂等键只留一条。
     *
     * <p>{@code ON CONFLICT DO NOTHING} 让回调重放在这一层就被识别出来——
     * 返回 0 即「这条回调之前收到过」，是幂等的第一道闸。
     *
     * <p>用注解 SQL 而不是 MP 的 {@code insert}，因为需要 PostgreSQL 的 {@code ON CONFLICT}。
     *
     * @return 1 = 首次收到；0 = 重复投递
     */
    @Insert("""
            INSERT INTO pay_callback_log
                (id, pay_no, channel_code, idempotent_key, raw_body, sign_valid,
                 channel_status, process_status, reject_reason, tenant_id,
                 del_flag, create_time, update_time)
            VALUES
                (#{log.id}, #{log.payNo}, #{log.channelCode}, #{log.idempotentKey},
                 #{log.rawBody}, #{log.signValid}, #{log.channelStatus},
                 #{log.processStatus}, #{log.rejectReason}, #{log.tenantId},
                 '0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (channel_code, idempotent_key) DO NOTHING
            """)
    int insertIfAbsent(@Param("log") PayCallbackLog log);

    /**
     * 把一条「没被接受过」的回调留痕重新认领为「处理中」（「只有 ACCEPTED / ACCEPTED_DUP 占幂等位」）。
     *
     * <p>留痕同时是幂等第一道闸：此前一条回调只要被拒过（核对不通过、回查未确认），渠道之后的重推
     * 都会被当成重复投递吞掉，这笔钱永远入不了账。现在只有「被接受过」的回调才算处理过：
     * 被拒（REJECTED）、非成功事件（IGNORED：失败 / 处理中）、处理到一半没落定（RECEIVED）的都允许重推时重新走一遍。
     *
     * <p>14.8 回放实测：某渠道以交易号为幂等键，同一笔交易先推「处理中」再推「成功」时，
     * 旧版只认领 REJECTED，前一条停在 RECEIVED 把成功回调当重复吞掉了。
     * 同一条回调并发重入是安全的：入账在业务单锁内由支付单状态机条件更新，第二次只会得到 NOOP（重复）。
     *
     * @return 1 = 认领成功，按首次处理；0 = 这条回调已被接受（ACCEPTED / ACCEPTED_DUP），按重复投递处理
     */
    @Update("""
            UPDATE pay_callback_log
               SET process_status = 'RECEIVED',
                   reject_reason = NULL,
                   update_time = CURRENT_TIMESTAMP
             WHERE channel_code = #{channelCode}
               AND idempotent_key = #{idempotentKey}
               AND process_status NOT IN ('ACCEPTED', 'ACCEPTED_DUP')
               AND del_flag = '0'
            """)
    int reclaimNotAccepted(@Param("channelCode") String channelCode,
                           @Param("idempotentKey") String idempotentKey);
}
