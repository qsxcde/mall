package com.geekmall.modules.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.payment.entity.PaymentRefundRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 退款单 Mapper。
 */
@Mapper
public interface PaymentRefundRecordMapper extends BaseMapper<PaymentRefundRecord> {

    /**
     * 选取可重试的退款单。
     *
     * <p>包含「失败」与「处理中但已超过下次重试时间」两类：后者覆盖进程在
     * 渠道调用之后、结算落库之前崩溃的窗口。</p>
     */
    @Select(
            """
            SELECT *
            FROM pay_refund_record
            WHERE deleted = 0
              AND status IN (0, 2)
              AND retry_count < #{maxRetry}
              AND (next_retry_time IS NULL OR next_retry_time <= NOW())
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<PaymentRefundRecord> selectRetryable(@Param("maxRetry") int maxRetry, @Param("limit") int limit);

    /**
     * 抢占一条重试任务。
     *
     * <p>用 {@code retry_count} 做乐观锁：只有读到旧值的那个实例能更新成功，
     * 从而保证同一退款单在同一时刻只有一个执行者。抢占后立即写入下次重试时间，
     * 即使本次渠道调用超时，也不会被其它实例立刻重复拾取。</p>
     *
     * @return 1 表示抢占成功，0 表示已被其它执行者抢走或状态已变化
     */
    @Update(
            """
            UPDATE pay_refund_record
            SET retry_count = retry_count + 1,
                next_retry_time = #{nextRetryTime},
                update_time = NOW()
            WHERE id = #{id}
              AND retry_count = #{expectedRetryCount}
              AND status IN (0, 2)
              AND deleted = 0
            """)
    int claimRetry(
            @Param("id") Long id,
            @Param("expectedRetryCount") int expectedRetryCount,
            @Param("nextRetryTime") LocalDateTime nextRetryTime);
}
