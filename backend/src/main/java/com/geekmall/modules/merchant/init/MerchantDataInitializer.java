package com.geekmall.modules.merchant.init;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.modules.merchant.entity.MerchantUser;
import com.geekmall.modules.merchant.entity.Shop;
import com.geekmall.modules.merchant.mapper.MerchantUserMapper;
import com.geekmall.modules.merchant.mapper.ShopMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 商家演示数据初始化：确保存在演示店铺与商家账号，便于商家端联调。
 *
 * <p>账号：merchant / 123456；仅当配置 {@code mall.init-demo-merchant=true} 时启用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.init-demo-merchant", havingValue = "true")
public class MerchantDataInitializer implements ApplicationRunner {

    private static final String DEMO_ACCOUNT = "merchant";
    private static final String DEMO_PASSWORD = "123456";
    private static final long DEMO_SHOP_ID = 1L;

    private final MerchantUserMapper merchantUserMapper;
    private final ShopMapper shopMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        Shop shop = shopMapper.selectById(DEMO_SHOP_ID);
        if (shop == null) {
            shop = new Shop();
            shop.setId(DEMO_SHOP_ID);
            shop.setName("极客数码旗舰店");
            shop.setLogo("旗");
            shop.setLevel("金牌卖家");
            shop.setVerified(1);
            shop.setTodayTarget(new BigDecimal("1000000"));
            shop.setStatus(1);
            shopMapper.insert(shop);
            log.info("已初始化演示店铺：{}", shop.getName());
        }

        MerchantUser user = merchantUserMapper.selectOne(new LambdaQueryWrapper<MerchantUser>()
                .eq(MerchantUser::getUsername, DEMO_ACCOUNT)
                .last("limit 1"));
        if (user == null) {
            user = new MerchantUser();
            user.setUsername(DEMO_ACCOUNT);
            user.setPassword(passwordEncoder.encode(DEMO_PASSWORD));
            user.setNickname("陈掌柜");
            user.setAvatar("陈");
            user.setRole("超级管理员");
            user.setShopId(DEMO_SHOP_ID);
            user.setStatus(1);
            merchantUserMapper.insert(user);
            log.info("已初始化演示商家账号：{} / {}", DEMO_ACCOUNT, DEMO_PASSWORD);
        }
    }
}
