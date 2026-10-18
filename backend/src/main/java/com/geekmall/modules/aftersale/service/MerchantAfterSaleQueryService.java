package com.geekmall.modules.aftersale.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.geekmall.modules.aftersale.entity.AfterSale;
import java.util.Collection;
import java.util.List;

/**
 * 面向<b>商家端</b>的售后只读查询服务。
 *
 * <p>与 {@code MerchantOrderQueryService} / {@code MerchantReviewQueryService} 同一设计：
 * 商家域不再直接 {@code import AfterSaleMapper}，{@code oms_aftersale} 的店铺隔离条件
 * 由售后域自己保证。</p>
 *
 * <p><b>状态流转不走这里</b>：必须用 {@code AfterSaleMapper.transferStatus} 的 CAS 路径
 * （它拼接了 {@code MerchantAftersaleStatus.STATUS_SQL} 口径），否则会绕过状态机与并发保护。</p>
 */
public interface MerchantAfterSaleQueryService {

    /** 按店铺 + 条件分页。 */
    IPage<AfterSale> pageByShop(Long shopId, LambdaQueryWrapper<AfterSale> wrapper, long page, long size);

    /** 按店铺 + 条件查询列表。 */
    List<AfterSale> listByShop(Long shopId, LambdaQueryWrapper<AfterSale> wrapper);

    /** 按店铺 + 售后单 ID 查询，不存在返回 null。 */
    AfterSale findByShopAndId(Long shopId, Long id);

    /** 按店铺 + 售后单 ID 批量查询。 */
    List<AfterSale> listByShopAndIds(Long shopId, Collection<Long> ids);

    /**
     * 查询指定订单号集合中「仍在处理中」的售后单（供商家订单列表标记「售后中」）。
     */
    List<AfterSale> listDoingByOrderNos(Long shopId, Collection<String> orderNos);

    /**
     * 商家侧状态流转（CAS）。
     *
     * <p><b>这是商家域唯一允许的写操作</b>，且必须走 {@code AfterSaleMapper.transferStatus} ——
     * 它把 {@code MerchantAftersaleStatus.STATUS_SQL} 口径拼进 WHERE 条件，
     * 保证「界面显示的不可操作」与「后端拒绝操作」完全一致。
     * 直接在商家域写 {@code updateById} 会绕过该口径，造成状态误判。</p>
     *
     * @return 影响行数，0 表示状态已被变更或不允许该流转
     */
    int transferStatus(
            Long id,
            Long shopId,
            String expectedStatus,
            String targetStatus,
            Integer buyerStatus,
            String rejectReason,
            java.math.BigDecimal refundAmount);
}
