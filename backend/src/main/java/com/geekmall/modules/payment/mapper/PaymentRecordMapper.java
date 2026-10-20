package com.geekmall.modules.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.payment.entity.PaymentRecord;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 支付单 Mapper。
 */
@Mapper
public interface PaymentRecordMapper extends BaseMapper<PaymentRecord> {

    /**
     * 找出「支付成功但资金状态仍需修正」的支付单。
     *
     * <p>命中三类：订单仍待支付（status=0，需要重新落账）；订单已关闭且尚未发起退款
     * （status=5 且 refund_status=0，需要转退款）；订单已由另一笔支付落账
     * （{@code p.trade_no != o.trade_no}，即重复支付，需要退款）。已经落账或已退款的单
     * 不会再被扫到，因此任务可以安全地周期性重复执行。</p>
     *
     * @param since 只扫描该时间之后创建的支付单
     * @param limit 单轮上限
     */
    @Select(
            """
            SELECT p.*
            FROM pay_payment_record p
            JOIN oms_order o ON o.order_no = p.order_no AND o.deleted = 0
            WHERE p.deleted = 0
              AND p.status = 1
              AND (
                    o.status = 0
                    OR (o.status = 5 AND p.refund_status = 0)
                    OR (o.trade_no IS NOT NULL AND p.trade_no <> o.trade_no AND p.refund_status = 0)
                  )
              AND p.create_time >= #{since}
            ORDER BY p.id ASC
            LIMIT #{limit}
            """)
    List<PaymentRecord> selectSuccessWithUnsettledOrder(@Param("since") LocalDateTime since, @Param("limit") int limit);

    /** 原子累加支付单的累计退款金额（避免并发退款时读改写丢失）。 */
    @Update("UPDATE pay_payment_record SET refund_amount = refund_amount + #{amount}, update_time = NOW() "
            + "WHERE id = #{id} AND deleted = 0")
    int addRefundAmount(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /** 更新整单退款状态（仅全额退款使用；部分售后退款不修改该字段）。 */
    @Update("UPDATE pay_payment_record SET refund_status = #{status}, update_time = NOW() "
            + "WHERE id = #{id} AND deleted = 0")
    int updateRefundStatus(@Param("id") Long id, @Param("status") int status);
}
