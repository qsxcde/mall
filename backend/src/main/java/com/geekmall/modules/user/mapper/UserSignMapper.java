package com.geekmall.modules.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.user.entity.UserSign;
import org.apache.ibatis.annotations.Mapper;

/**
 * 签到记录 Mapper。
 */
@Mapper
public interface UserSignMapper extends BaseMapper<UserSign> {
}
