package com.geekmall.modules.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.user.entity.UserFavorite;
import org.apache.ibatis.annotations.Mapper;

/**
 * 收藏 Mapper。
 */
@Mapper
public interface UserFavoriteMapper extends BaseMapper<UserFavorite> {
}
