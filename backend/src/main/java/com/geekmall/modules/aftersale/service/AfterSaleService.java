package com.geekmall.modules.aftersale.service;

import com.geekmall.modules.aftersale.dto.ApplyAfterSaleDTO;
import com.geekmall.modules.aftersale.dto.MarkReturnedDTO;
import com.geekmall.modules.aftersale.vo.AfterSaleVO;
import java.util.List;

/**
 * 售后服务。
 */
public interface AfterSaleService {

    /**
     * 申请售后。
     *
     * @return 售后单 ID（前端路由 /aftersale/:id 使用）
     */
    Long apply(Long userId, ApplyAfterSaleDTO dto);

    /** 我的售后列表，status 为 null 时返回全部。 */
    List<AfterSaleVO> list(Long userId, Integer status);

    /** 售后详情（含处理进度时间轴）。 */
    AfterSaleVO detail(Long userId, Long id);

    /** 取消申请（处理中 / 待寄回可取消）。 */
    void cancel(Long userId, Long id);

    /**
     * 买家已寄回商品（退货退款 / 换货 / 维修共用，凡「商家同意后需买家寄回」的类型都走这一步）。
     *
     * <p>没有这一步，商家侧的「确认收货」（wait_receive → done）就是<b>永远走不到的死路径</b>：
     * 同意退货后状态停在 wait_return，而没有任何接口能把它推进到 wait_receive，
     * 退货退款的钱就永远退不出去。</p>
     *
     * @param dto 退货物流信息（快递公司 / 运单号，均选填），供商家确认收货时核对
     */
    void markReturned(Long userId, Long id, MarkReturnedDTO dto);

    /**
     * 售后审批通过后触发退款（供商家域在事务提交后调用）。
     *
     * <p><b>为什么由本域承担责任</b>：售后域持有 {@code oms_aftersale} 的语义，
     * 知道「哪些类型涉及退款、退多少」，支付域只认「订单号 + 金额」。
     * 把类型判断放在商家域会让同一套规则出现两处实现。</p>
     *
     * <p>幂等由支付域的 {@code refund_status} CAS + {@code refund_no} 唯一索引保证，
     * 因此本方法可安全重试。</p>
     *
     * @return 本次是否真的发起了退款（false = 类型不涉及退款 / 未支付 / 已退过）
     */
    boolean refundAfterSale(Long afterSaleId);
}
