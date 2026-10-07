package com.geekmall.modules.merchant.service;

import com.geekmall.modules.merchant.dto.MerchantLoginDTO;
import com.geekmall.modules.merchant.vo.MerchantLoginVO;
import com.geekmall.modules.merchant.vo.MerchantProfileVO;
import com.geekmall.modules.merchant.vo.ShopVO;

/**
 * 商家端认证与店铺信息服务。
 */
public interface MerchantAuthService {

    /** 商家登录，签发商家令牌。 */
    MerchantLoginVO login(MerchantLoginDTO dto);

    /** 当前店铺信息。 */
    ShopVO currentShop();

    /** 当前登录商家资料。 */
    MerchantProfileVO currentProfile();

    /** 商家登出：删除服务端会话。 */
    void logout();
}
