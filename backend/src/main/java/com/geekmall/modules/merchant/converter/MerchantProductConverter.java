package com.geekmall.modules.merchant.converter;

import com.geekmall.modules.merchant.vo.MerchantProductVO;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.service.SkuService;
import org.springframework.util.StringUtils;

/**
 * 商家端商品对象转换。
 */
public final class MerchantProductConverter {

    private MerchantProductConverter() {}

    /** 缩略图配色键数量，与前端 ProductThumb 的渐变样式对应。 */
    private static final int THUMB_VARIANTS = 6;

    public static MerchantProductVO toVO(Product product) {
        return toVO(product, null);
    }

    /**
     * 商品 → 商家端行（含规格结构回显）。
     *
     * @param spec 商品的规格维度与 SKU 行；无规格商品传 {@code null}
     */
    public static MerchantProductVO toVO(Product product, SkuService.SpecStructure spec) {
        if (product == null) {
            return null;
        }
        MerchantProductVO vo = new MerchantProductVO();
        vo.setId(product.getId());
        vo.setCode("GK" + (10000 + product.getId()));
        vo.setName(product.getTitle());
        vo.setSpec(product.getSpec());
        vo.setCat(product.getCategoryName());
        vo.setBrand(product.getBrandName());
        vo.setPrice(product.getPrice());
        vo.setListPrice(product.getOldPrice());
        vo.setCost(product.getCost());
        vo.setStock(product.getStock() == null ? 0 : product.getStock());
        vo.setSafeStock(product.getSafeStock() == null ? 0 : product.getSafeStock());
        vo.setSales(product.getSales() == null ? 0 : product.getSales());
        vo.setViews(product.getViews() == null ? 0 : product.getViews());
        vo.setCover(product.getCover());
        vo.setThumb(thumb(product.getId()));
        vo.setTag(tag(product));
        vo.setStatus(product.getMerchantStatus() == null ? "on" : product.getMerchantStatus());
        // 规格结构回显：SKU 矩阵编辑器靠它还原「维度 + 取值 + 已填价格/库存/成本」
        vo.setSpecDimensions(spec == null ? java.util.List.of() : spec.dimensions());
        vo.setSkus(spec == null ? java.util.List.of() : spec.skus());
        // 版本号回传给前端，编辑提交时原样带回，服务端据此判断是否被他人改过
        vo.setVersion(product.getVersion() == null ? 0 : product.getVersion());
        vo.setUpdatedAt(product.getUpdateTime());
        return vo;
    }

    private static String thumb(Long id) {
        long seed = id == null ? 0 : id;
        return "th" + (seed % THUMB_VARIANTS + 1);
    }

    private static String tag(Product product) {
        if (StringUtils.hasText(product.getTags())) {
            String first = product.getTags().split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return product.getBrandName();
    }
}
