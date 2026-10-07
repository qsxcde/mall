package com.geekmall.modules.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.user.entity.UserHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 浏览足迹 Mapper。
 */
@Mapper
public interface UserHistoryMapper extends BaseMapper<UserHistory> {
}
