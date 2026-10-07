package com.geekmall.modules.merchant.service;

import com.geekmall.modules.merchant.dto.MerchantProductQueryDTO;
import com.geekmall.modules.merchant.dto.ProductBatchDTO;
import com.geekmall.modules.merchant.dto.ProductSaveDTO;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import com.geekmall.modules.merchant.vo.MerchantProductVO;

import java.util.Map;

/**
 * 商家端商品管理服务。
 */
public interface MerchantProductService {

    /** 商品分页（含 Tab 计数与统计条）。 */
    MerchantPageVO<MerchantProductVO> page(MerchantProductQueryDTO query);

    /** 筛选下拉：分类 / 品牌。 */
    Map<String, Object> filters();

    /** 新增或编辑商品，返回商品 ID。 */
    Long save(ProductSaveDTO dto);

    /** 变更商品状态（上下架 / 移入回收站 / 恢复）。 */
    int updateStatus(ProductBatchDTO dto);

    /** 批量改价。 */
    int batchUpdatePrice(ProductBatchDTO dto);
}
