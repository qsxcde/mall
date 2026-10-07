package com.geekmall.modules.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.review.entity.Review;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评价 Mapper。
 */
@Mapper
public interface ReviewMapper extends BaseMapper<Review> {
}
