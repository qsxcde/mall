package com.geekmall.modules.product.service;

import com.geekmall.modules.product.dto.SkuSaveDTO;
import com.geekmall.modules.product.dto.SpecDimensionSaveDTO;
import com.geekmall.modules.product.vo.SkuEditVO;
import com.geekmall.modules.product.vo.SpecDimensionVO;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 规格维度与 SKU 的读写服务（商品域对内收口）。
 *
 * <p>与 {@link ProductStockService} 的分工：本服务管「规格结构怎么落库、怎么读」，
 * 库存的 CAS 扣减 / 回补在 {@link SkuStockService}。两者同域同纪律 ——
 * 跨域（商家 / 交易 / 库存）一律走服务，不得直连本域 Mapper。</p>
 *
 * <p><b>落库语义是覆盖式重建</b>：整商品退休旧结构（逻辑删）后按自然键逐条 upsert
 * （维度名 / 维度+取值 / 组合键），upsert 会复活同名或同组合的旧行 ——
 * 逻辑删除行仍占用唯一键，若只做「删了再插」，二次保存会撞唯一索引。</p>
 */
public interface SkuService {

    /**
     * 商品主表的 SKU 汇总：起售价 = {@code MIN(sku.price)}、总库存 = {@code SUM(sku.stock)}、
     * 规格文本 = 默认 SKU（首个）的 {@code spec_desc}。
     */
    record SkuAggregate(BigDecimal price, Integer stock, String spec) {}

    /** 商品规格结构读模型：维度（含值与图片）+ SKU 行。 */
    record SpecStructure(List<SpecDimensionVO> dimensions, List<SkuEditVO> skus) {

        /** 无规格商品的空结构（列表回显与详情都可直接遍历）。 */
        public static final SpecStructure EMPTY = new SpecStructure(List.of(), List.of());
    }

    /**
     * 覆盖式保存商品的规格结构（维度 / 取值 / SKU / 关联），并返回需回写商品主表的汇总。
     *
     * @param defaultSpecDesc 组合无规格值时的兜底规格文本（取商品的 {@code spec}，为空用「默认规格」）
     */
    SkuAggregate replaceSkus(
            Long productId,
            Long shopId,
            String defaultSpecDesc,
            List<SpecDimensionSaveDTO> specDimensions,
            List<SkuSaveDTO> skus);

    /**
     * 按商品现有 SKU 行汇总；商品没有 SKU 时返回 {@code null}。
     *
     * <p>供「未传 SKU 的保存」保持守恒：已有 SKU 的商品，价格 / 库存 / 规格文本
     * 一律以 SKU 为准，不信任前端提交的商品级数值。</p>
     */
    SkuAggregate aggregateOf(Long productId);

    /** 单商品规格结构（买家详情 / 商家编辑回显）。 */
    SpecStructure specStructureOf(Long productId);

    /** 批量读规格结构，返回 {@code productId -> 结构}；无 SKU 的商品不在结果中。 */
    Map<Long, SpecStructure> specStructureByProductIds(Collection<Long> productIds);
}
