package com.geekmall.modules.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.product.entity.Category;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分类 Mapper。
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}
