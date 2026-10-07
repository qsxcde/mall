package com.geekmall.modules.merchant.converter;

import com.geekmall.modules.merchant.entity.MerchantUser;
import com.geekmall.modules.merchant.entity.Shop;
import com.geekmall.modules.merchant.vo.MerchantProfileVO;
import com.geekmall.modules.merchant.vo.ShopVO;

/**
 * 商家账号 / 店铺对象转换。
 */
public final class MerchantConverter {

    private MerchantConverter() {
    }

    public static ShopVO toShopVO(Shop shop) {
        if (shop == null) {
            return null;
        }
        ShopVO vo = new ShopVO();
        vo.setId(shop.getId());
        vo.setName(shop.getName());
        vo.setLogo(shop.getLogo());
        vo.setLevel(shop.getLevel());
        vo.setVerified(shop.getVerified() != null && shop.getVerified() == 1);
        vo.setTodayTarget(shop.getTodayTarget());
        return vo;
    }

    public static MerchantProfileVO toProfile(MerchantUser user, Shop shop) {
        if (user == null) {
            return null;
        }
        MerchantProfileVO vo = new MerchantProfileVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setRole(user.getRole());
        vo.setShopId(user.getShopId());
        vo.setShopName(shop == null ? null : shop.getName());
        return vo;
    }
}
