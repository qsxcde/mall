package com.geekmall.modules.product.converter;

import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.vo.CategoryVO;
import com.geekmall.modules.product.vo.ProductCardVO;
import com.geekmall.modules.product.vo.ProductDetailVO;

import java.util.Arrays;
import java.util.List;

/**
 * 商品对象转换。
 */
public final class ProductConverter {

    private ProductConverter() {
    }

    public static ProductCardVO toCard(Product product) {
        if (product == null) {
            return null;
        }
        ProductCardVO vo = new ProductCardVO();
        fillBase(vo, product);
        return vo;
    }

    public static ProductDetailVO toDetail(Product product) {
        if (product == null) {
            return null;
        }
        ProductDetailVO vo = new ProductDetailVO();
        fillBase(vo, product);
        vo.setCategoryId(product.getCategoryId());
        vo.setCategoryKey(product.getCategoryKey());
        vo.setParentKey(product.getParentKey());
        vo.setBrandId(product.getBrandId());
        vo.setDescription(product.getTitle());
        vo.setHot(isOn(product.getIsHot()));
        vo.setIsNew(isOn(product.getIsNew()));
        vo.setDisplayPrice(product.getPrice());
        vo.setStock(product.getStock());
        vo.setImages(product.getCover() == null ? List.of() : List.of(product.getCover()));
        return vo;
    }

    public static CategoryVO toCategoryVO(com.geekmall.modules.product.entity.Category category) {
        if (category == null) {
            return null;
        }
        CategoryVO vo = new CategoryVO();
        vo.setId(category.getId());
        vo.setCategoryKey(category.getCategoryKey());
        vo.setName(category.getName());
        vo.setDescription(category.getDescription());
        vo.setIcon(category.getIcon());
        return vo;
    }

    public static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static void fillBase(ProductCardVO vo, Product product) {
        vo.setId(product.getId());
        vo.setTitle(product.getTitle());
        vo.setCover(product.getCover());
        vo.setPrice(product.getPrice());
        vo.setOldPrice(product.getOldPrice());
        vo.setSpec(product.getSpec());
        vo.setSales(product.getSales());
        vo.setRating(product.getRating());
        vo.setBrand(product.getBrandName());
        vo.setCat(product.getCategoryName());
        vo.setTags(splitTags(product.getTags()));
    }

    private static boolean isOn(Integer flag) {
        return flag != null && flag == 1;
    }
}
