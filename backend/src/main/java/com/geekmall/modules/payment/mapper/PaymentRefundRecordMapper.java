package com.geekmall.modules.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.payment.entity.PaymentRefundRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 退款单 Mapper。
 */
@Mapper
public interface PaymentRefundRecordMapper extends BaseMapper<PaymentRefundRecord> {}
