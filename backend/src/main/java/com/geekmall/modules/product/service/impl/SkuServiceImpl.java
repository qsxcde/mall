package com.geekmall.modules.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.product.dto.SkuSaveDTO;
import com.geekmall.modules.product.dto.SpecDimensionSaveDTO;
import com.geekmall.modules.product.entity.Sku;
import com.geekmall.modules.product.entity.SkuSpec;
import com.geekmall.modules.product.entity.SpecDimension;
import com.geekmall.modules.product.entity.SpecValue;
import com.geekmall.modules.product.mapper.SkuMapper;
import com.geekmall.modules.product.mapper.SkuSpecMapper;
import com.geekmall.modules.product.mapper.SpecDimensionMapper;
import com.geekmall.modules.product.mapper.SpecValueMapper;
import com.geekmall.modules.product.service.SkuService;
import com.geekmall.modules.product.vo.SkuEditVO;
import com.geekmall.modules.product.vo.SpecDimensionVO;
import com.geekmall.modules.product.vo.SpecValueVO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 规格维度与 SKU 读写实现。
 *
 * <p>写路径的关键取舍见 {@link SkuService}：覆盖式重建 + 按自然键 upsert 复活旧行，
 * 既保证「保存即生效」的整单语义，又不与逻辑删除行占用的唯一键冲突。</p>
 */
@Service
@RequiredArgsConstructor
public class SkuServiceImpl implements SkuService {

    /** 维度数量上限：笛卡尔积规模必须受控（颜色 × 尺寸 × 内存已足够表达）。 */
    private static final int MAX_DIMENSIONS = 3;

    /** 单维度取值数量上限。 */
    private static final int MAX_VALUES_PER_DIMENSION = 20;

    /** 组合无任何规格值时的兜底规格文本。 */
    private static final String DEFAULT_SPEC_DESC = "默认规格";

    private final SpecDimensionMapper dimensionMapper;
    private final SpecValueMapper valueMapper;
    private final SkuMapper skuMapper;
    private final SkuSpecMapper skuSpecMapper;

    /* ============================== 写路径 ============================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkuAggregate replaceSkus(
            Long productId,
            Long shopId,
            String defaultSpecDesc,
            List<SpecDimensionSaveDTO> specDimensions,
            List<SkuSaveDTO> skus) {
        if (productId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "商品 ID 不能为空");
        }
        List<Dimension> dimensions = normalizeDimensions(specDimensions);
        List<Row> rows = normalizeRows(dimensions, skus, defaultSpecDesc);

        // ① 整商品退休旧结构（逻辑删），随后 upsert 会复活仍在用的行
        skuSpecMapper.retireByProduct(productId);
        skuMapper.retireByProduct(productId);
        valueMapper.retireByProduct(productId);
        dimensionMapper.retireByProduct(productId);

        // ② 维度 / 取值：按自然键 upsert 后回读 ID
        Map<String, Long> dimensionIds = new LinkedHashMap<>();
        Map<String, Map<String, Long>> valueIds = new LinkedHashMap<>();
        for (int i = 0; i < dimensions.size(); i++) {
            Dimension dimension = dimensions.get(i);
            dimensionMapper.upsert(productId, shopId, dimension.name(), i);
            Long dimensionId = dimensionMapper
                    .selectOne(new LambdaQueryWrapper<SpecDimension>()
                            .eq(SpecDimension::getProductId, productId)
                            .eq(SpecDimension::getName, dimension.name())
                            .last("limit 1"))
                    .getId();
            dimensionIds.put(dimension.name(), dimensionId);
            Map<String, Long> byValue = new LinkedHashMap<>();
            for (int j = 0; j < dimension.values().size(); j++) {
                SpecDimensionSaveDTO.Item item = dimension.values().get(j);
                valueMapper.upsert(dimensionId, productId, item.getValue(), item.getImage(), sortOf(item, j));
                Long valueId = valueMapper
                        .selectOne(new LambdaQueryWrapper<SpecValue>()
                                .eq(SpecValue::getDimensionId, dimensionId)
                                .eq(SpecValue::getValue, item.getValue())
                                .last("limit 1"))
                        .getId();
                byValue.put(item.getValue(), valueId);
            }
            valueIds.put(dimension.name(), byValue);
        }

        // ③ SKU 与关联：组合唯一键 = 排序后的规格值 ID 用 | 拼接
        for (Row row : rows) {
            List<Long> ids = new ArrayList<>();
            for (Dimension dimension : dimensions) {
                ids.add(valueIds.get(dimension.name()).get(row.specValues().get(dimension.name())));
            }
            List<Long> sortedIds = new ArrayList<>(new TreeSet<>(ids));
            String specKeys =
                    String.join("|", sortedIds.stream().map(String::valueOf).toList());
            String skuCode = "SKU-" + productId + "-"
                    + (sortedIds.isEmpty()
                            ? "D"
                            : String.join(
                                    "-", sortedIds.stream().map(String::valueOf).toList()));
            skuMapper.upsert(
                    productId, shopId, skuCode, row.specDesc(), specKeys, row.price(), row.cost(), row.stock(), 0);
            Long skuId = skuMapper
                    .selectOne(new LambdaQueryWrapper<Sku>()
                            .eq(Sku::getProductId, productId)
                            .eq(Sku::getSpecKeys, specKeys)
                            .last("limit 1"))
                    .getId();
            for (Dimension dimension : dimensions) {
                String value = row.specValues().get(dimension.name());
                skuSpecMapper.upsert(
                        skuId,
                        productId,
                        valueIds.get(dimension.name()).get(value),
                        dimensionIds.get(dimension.name()),
                        value);
            }
        }

        // ④ 汇总回写商品主表的口径：起售价 / 总库存 / 默认 SKU 的规格文本
        BigDecimal minPrice =
                rows.stream().map(Row::price).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        int totalStock = rows.stream().mapToInt(Row::stock).sum();
        return new SkuAggregate(minPrice, totalStock, rows.get(0).specDesc());
    }

    @Override
    public SkuAggregate aggregateOf(Long productId) {
        List<Sku> skus = skuMapper.selectList(
                new LambdaQueryWrapper<Sku>().eq(Sku::getProductId, productId).orderByAsc(Sku::getId));
        if (skus.isEmpty()) {
            return null;
        }
        BigDecimal minPrice = skus.stream()
                .map(sku -> sku.getPrice() == null ? BigDecimal.ZERO : sku.getPrice())
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        int totalStock = skus.stream()
                .mapToInt(sku -> sku.getStock() == null ? 0 : sku.getStock())
                .sum();
        return new SkuAggregate(minPrice, totalStock, skus.get(0).getSpecDesc());
    }

    /* ============================== 读路径 ============================== */

    @Override
    public SpecStructure specStructureOf(Long productId) {
        Map<Long, SpecStructure> map = specStructureByProductIds(List.of(productId));
        SpecStructure structure = map.get(productId);
        return structure == null ? SpecStructure.EMPTY : structure;
    }

    @Override
    public Map<Long, SpecStructure> specStructureByProductIds(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = productIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }

        List<SpecDimension> dimensions = dimensionMapper.selectList(new LambdaQueryWrapper<SpecDimension>()
                .in(SpecDimension::getProductId, ids)
                .orderByAsc(SpecDimension::getSort)
                .orderByAsc(SpecDimension::getId));
        List<SpecValue> values = valueMapper.selectList(new LambdaQueryWrapper<SpecValue>()
                .in(SpecValue::getProductId, ids)
                .orderByAsc(SpecValue::getSort)
                .orderByAsc(SpecValue::getId));
        List<Sku> skus = skuMapper.selectList(
                new LambdaQueryWrapper<Sku>().in(Sku::getProductId, ids).orderByAsc(Sku::getId));
        List<SkuSpec> skuSpecs =
                skuSpecMapper.selectList(new LambdaQueryWrapper<SkuSpec>().in(SkuSpec::getProductId, ids));

        Map<Long, List<SpecValue>> valuesByDimension = new HashMap<>();
        for (SpecValue value : values) {
            valuesByDimension
                    .computeIfAbsent(value.getDimensionId(), k -> new ArrayList<>())
                    .add(value);
        }
        Map<Long, Map<String, String>> specValuesBySku = new HashMap<>();
        Map<Long, String> dimensionNameById = new HashMap<>();
        for (SpecDimension dimension : dimensions) {
            dimensionNameById.put(dimension.getId(), dimension.getName());
        }
        for (SkuSpec skuSpec : skuSpecs) {
            String dimensionName = dimensionNameById.get(skuSpec.getDimensionId());
            if (dimensionName != null) {
                specValuesBySku
                        .computeIfAbsent(skuSpec.getSkuId(), k -> new LinkedHashMap<>())
                        .put(dimensionName, skuSpec.getValueSnapshot());
            }
        }

        Map<Long, SpecStructure> result = new LinkedHashMap<>();
        for (Long productId : ids) {
            List<SpecDimensionVO> dimensionVos = new ArrayList<>();
            for (SpecDimension dimension : dimensions) {
                if (!productId.equals(dimension.getProductId())) {
                    continue;
                }
                SpecDimensionVO vo = new SpecDimensionVO();
                vo.setName(dimension.getName());
                vo.setValues(valuesByDimension.getOrDefault(dimension.getId(), List.of()).stream()
                        .map(value -> new SpecValueVO(value.getValue(), value.getImage()))
                        .toList());
                dimensionVos.add(vo);
            }
            List<SkuEditVO> skuVos = new ArrayList<>();
            for (Sku sku : skus) {
                if (!productId.equals(sku.getProductId())) {
                    continue;
                }
                SkuEditVO vo = new SkuEditVO();
                vo.setId(sku.getId());
                vo.setSpecDesc(sku.getSpecDesc());
                vo.setSpecValues(specValuesBySku.getOrDefault(sku.getId(), Map.of()));
                vo.setPrice(sku.getPrice());
                vo.setCost(sku.getCost());
                vo.setStock(sku.getStock());
                skuVos.add(vo);
            }
            if (!dimensionVos.isEmpty() || !skuVos.isEmpty()) {
                result.put(productId, new SpecStructure(dimensionVos, skuVos));
            }
        }
        return result;
    }

    /* ============================== 校验与规范化 ============================== */

    /** 维度列表规范化：重名 / 空值 / 规模超限都在这里拦下，避免脏结构落库。 */
    private List<Dimension> normalizeDimensions(List<SpecDimensionSaveDTO> specDimensions) {
        if (specDimensions == null || specDimensions.isEmpty()) {
            return List.of();
        }
        if (specDimensions.size() > MAX_DIMENSIONS) {
            throw new BizException(ResultCode.PARAM_ERROR, "规格维度最多 " + MAX_DIMENSIONS + " 个");
        }
        List<Dimension> dimensions = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (SpecDimensionSaveDTO dto : specDimensions) {
            String name = dto.getName() == null ? "" : dto.getName().trim();
            if (!StringUtils.hasText(name)) {
                throw new BizException(ResultCode.PARAM_ERROR, "维度名不能为空");
            }
            if (!names.add(name)) {
                throw new BizException(ResultCode.PARAM_ERROR, "规格维度重复：" + name);
            }
            List<SpecDimensionSaveDTO.Item> values = dto.getValues() == null ? List.of() : dto.getValues();
            if (values.isEmpty()) {
                throw new BizException(ResultCode.PARAM_ERROR, "维度「" + name + "」缺少取值");
            }
            if (values.size() > MAX_VALUES_PER_DIMENSION) {
                throw new BizException(
                        ResultCode.PARAM_ERROR, "维度「" + name + "」的取值最多 " + MAX_VALUES_PER_DIMENSION + " 个");
            }
            Set<String> seen = new HashSet<>();
            List<SpecDimensionSaveDTO.Item> normalized = new ArrayList<>();
            for (SpecDimensionSaveDTO.Item item : values) {
                String value = item.getValue() == null ? "" : item.getValue().trim();
                if (!StringUtils.hasText(value)) {
                    throw new BizException(ResultCode.PARAM_ERROR, "维度「" + name + "」存在空取值");
                }
                if (!seen.add(value)) {
                    throw new BizException(ResultCode.PARAM_ERROR, "维度「" + name + "」的取值重复：" + value);
                }
                item.setValue(value);
                normalized.add(item);
            }
            dimensions.add(new Dimension(name, normalized));
        }
        return dimensions;
    }

    /** SKU 行规范化：组合必须完整覆盖所有维度，且组合互不重复。 */
    private List<Row> normalizeRows(List<Dimension> dimensions, List<SkuSaveDTO> skus, String defaultSpecDesc) {
        if (skus == null || skus.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "SKU 列表不能为空");
        }
        String fallback = StringUtils.hasText(defaultSpecDesc) ? defaultSpecDesc.trim() : DEFAULT_SPEC_DESC;
        List<Row> rows = new ArrayList<>();
        Set<String> combos = new HashSet<>();
        for (SkuSaveDTO sku : skus) {
            Map<String, String> specValues = sku.getSpecValues() == null ? Map.of() : sku.getSpecValues();
            Map<String, String> combo = new LinkedHashMap<>();
            List<String> descParts = new ArrayList<>();
            for (Dimension dimension : dimensions) {
                String value = specValues.get(dimension.name());
                if (!StringUtils.hasText(value)) {
                    throw new BizException(ResultCode.PARAM_ERROR, "SKU 缺少维度「" + dimension.name() + "」的取值");
                }
                if (dimension.values().stream()
                        .noneMatch(item -> item.getValue().equals(value.trim()))) {
                    throw new BizException(ResultCode.PARAM_ERROR, "维度「" + dimension.name() + "」未声明取值：" + value);
                }
                combo.put(dimension.name(), value.trim());
                descParts.add(value.trim());
            }
            for (String name : specValues.keySet()) {
                if (!dimensions.stream().anyMatch(dimension -> dimension.name().equals(name))) {
                    throw new BizException(ResultCode.PARAM_ERROR, "未知的规格维度：" + name);
                }
            }
            String comboKey = String.join("/", descParts);
            if (!combos.add(comboKey)) {
                throw new BizException(ResultCode.PARAM_ERROR, "SKU 规格组合重复：" + comboKey);
            }
            if (sku.getPrice() == null || sku.getPrice().signum() < 0) {
                throw new BizException(ResultCode.PARAM_ERROR, "SKU 售价不能为空且不能为负");
            }
            int stock = sku.getStock() == null ? 0 : sku.getStock();
            if (stock < 0) {
                throw new BizException(ResultCode.PARAM_ERROR, "SKU 库存不能为负");
            }
            BigDecimal cost = sku.getCost() == null ? BigDecimal.ZERO : sku.getCost();
            rows.add(new Row(
                    combo, descParts.isEmpty() ? fallback : String.join("/", descParts), sku.getPrice(), stock, cost));
        }
        return rows;
    }

    private int sortOf(SpecDimensionSaveDTO.Item item, int index) {
        return item.getSort() == null ? index : item.getSort();
    }

    /** 规范化后的维度：名称 + 取值。 */
    private record Dimension(String name, List<SpecDimensionSaveDTO.Item> values) {}

    /** 规范化后的 SKU 行。 */
    private record Row(Map<String, String> specValues, String specDesc, BigDecimal price, int stock, BigDecimal cost) {}
}
