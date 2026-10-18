package com.geekmall.modules.aftersale.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.geekmall.common.enums.MerchantAftersaleStatus;
import com.geekmall.modules.aftersale.entity.AfterSale;
import com.geekmall.modules.aftersale.mapper.AfterSaleMapper;
import com.geekmall.modules.aftersale.service.MerchantAfterSaleQueryService;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 商家端售后只读查询实现（所有查询强制 {@code shop_id} 隔离）。
 */
@Service
@RequiredArgsConstructor
public class MerchantAfterSaleQueryServiceImpl implements MerchantAfterSaleQueryService {

    private final AfterSaleMapper afterSaleMapper;

    @Override
    public IPage<AfterSale> pageByShop(Long shopId, LambdaQueryWrapper<AfterSale> wrapper, long page, long size) {
        return afterSaleMapper.selectPage(new Page<>(page, size), wrap(shopId, wrapper));
    }

    @Override
    public List<AfterSale> listByShop(Long shopId, LambdaQueryWrapper<AfterSale> wrapper) {
        return afterSaleMapper.selectList(wrap(shopId, wrapper));
    }

    @Override
    public AfterSale findByShopAndId(Long shopId, Long id) {
        return afterSaleMapper.selectOne(new LambdaQueryWrapper<AfterSale>()
                .eq(AfterSale::getShopId, shopId)
                .eq(AfterSale::getId, id)
                .last("limit 1"));
    }

    @Override
    public List<AfterSale> listByShopAndIds(Long shopId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return afterSaleMapper.selectList(new LambdaQueryWrapper<AfterSale>()
                .eq(AfterSale::getShopId, shopId)
                .in(AfterSale::getId, ids));
    }

    @Override
    public List<AfterSale> listDoingByOrderNos(Long shopId, Collection<String> orderNos) {
        if (orderNos == null || orderNos.isEmpty()) {
            return List.of();
        }
        // 用商家侧状态的统一口径判断「售后中」：
        // 只看 merchant_status 会把买家已撤销的工单误判为仍在处理中。
        return afterSaleMapper.selectList(new LambdaQueryWrapper<AfterSale>()
                .eq(AfterSale::getShopId, shopId)
                .in(AfterSale::getOrderNo, orderNos)
                .apply(MerchantAftersaleStatus.STATUS_SQL + " IN " + MerchantAftersaleStatus.NON_FINAL_IN));
    }

    @Override
    public int transferStatus(
            Long id,
            Long shopId,
            String expectedStatus,
            String targetStatus,
            Integer buyerStatus,
            String rejectReason,
            java.math.BigDecimal refundAmount) {
        return afterSaleMapper.transferStatus(
                id, shopId, expectedStatus, targetStatus, buyerStatus, rejectReason, refundAmount);
    }

    private static LambdaQueryWrapper<AfterSale> wrap(Long shopId, LambdaQueryWrapper<AfterSale> wrapper) {
        LambdaQueryWrapper<AfterSale> target = wrapper == null ? new LambdaQueryWrapper<>() : wrapper;
        return target.eq(AfterSale::getShopId, shopId);
    }
}
