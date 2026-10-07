package com.geekmall.modules.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.merchant.entity.MerchantUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商家账号 Mapper。
 */
@Mapper
public interface MerchantUserMapper extends BaseMapper<MerchantUser> {
}
