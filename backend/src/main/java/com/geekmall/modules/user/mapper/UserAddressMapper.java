package com.geekmall.modules.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.user.entity.UserAddress;
import org.apache.ibatis.annotations.Mapper;

/**
 * 收货地址 Mapper。
 */
@Mapper
public interface UserAddressMapper extends BaseMapper<UserAddress> {
}
