package com.geekmall.modules.aftersale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.aftersale.entity.AfterSale;
import com.geekmall.modules.merchant.support.MerchantAftersaleStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 售后 Mapper。
 */
@Mapper
public interface AfterSaleMapper extends BaseMapper<AfterSale> {

    /**
     * 商家侧工单状态流转（CAS）。
     *
     * <p>把「校验当前状态」与「更新」合并为一条带条件的 UPDATE，由数据库保证原子性：
     * 影响行数为 0 说明状态在读取后被他人改过（或本就不允许该动作），调用方据此拒绝。</p>
     *
     * <p>条件里的状态表达式直接复用商家侧的 {@link MerchantAftersaleStatus#STATUS_SQL}：
     * 买家侧创建的工单 merchant_status 为 NULL（需按 status 推导），买家撤销的工单
     * status 已变 2 但 merchant_status 未同步 —— 只有与列表展示用同一口径，
     * 才能保证「界面显示的不可操作」与「后端拒绝操作」完全一致。</p>
     *
     * @param expectedStatus 期望的当前状态，不匹配则不更新
     * @param targetStatus   目标商家侧状态
     * @param buyerStatus    目标买家侧数字状态（0 处理中 / 1 已完成 / 2 已取消）
     * @param rejectReason   拒绝理由，其他动作传空串
     * @param refundAmount   退款金额，为 null 表示不改动原金额
     * @return 影响行数，0 表示状态已被变更或不允许该流转
     */
    @Update("UPDATE oms_aftersale SET merchant_status = #{targetStatus}, status = #{buyerStatus}, "
            + "reject_reason = #{rejectReason}, "
            + "amount = COALESCE(#{refundAmount, jdbcType=DECIMAL}, amount), "
            + "update_time = NOW() "
            + "WHERE id = #{id} AND shop_id = #{shopId} AND deleted = 0 "
            + "AND " + MerchantAftersaleStatus.STATUS_SQL + " = #{expectedStatus}")
    int transferStatus(@Param("id") Long id,
                       @Param("shopId") Long shopId,
                       @Param("expectedStatus") String expectedStatus,
                       @Param("targetStatus") String targetStatus,
                       @Param("buyerStatus") Integer buyerStatus,
                       @Param("rejectReason") String rejectReason,
                       @Param("refundAmount") BigDecimal refundAmount);
}
