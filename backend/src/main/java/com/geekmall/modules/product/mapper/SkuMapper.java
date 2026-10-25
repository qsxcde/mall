package com.geekmall.modules.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.product.entity.Sku;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品 SKU Mapper。
 */
@Mapper
public interface SkuMapper extends BaseMapper<Sku> {}
