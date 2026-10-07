package com.geekmall.modules.user.init;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.modules.marketing.service.CouponService;
import com.geekmall.modules.user.entity.SysUser;
import com.geekmall.modules.user.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 演示数据初始化：确保存在演示账号与可用的演示优惠券，便于前端联调。
 *
 * <p>账号：13800000000 / 123456；仅当配置 mall.init-demo-user=true 时启用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.init-demo-user", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {

    private static final String DEMO_PHONE = "13800000000";
    private static final String DEMO_PASSWORD = "123456";

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final CouponService couponService;

    @Override
    public void run(ApplicationArguments args) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, DEMO_PHONE)
                .last("limit 1"));
        if (user == null) {
            user = createDemoUser();
            log.info("已初始化演示账号：{} / {}", DEMO_PHONE, DEMO_PASSWORD);
        } else {
            log.info("演示账号已存在：{}", DEMO_PHONE);
        }
        // 发放演示优惠券（内部幂等，重复启动不会重复发）
        couponService.grantDemoCoupons(user.getId());
    }

    private SysUser createDemoUser() {
        SysUser user = new SysUser();
        user.setUsername(DEMO_PHONE);
        user.setPhone(DEMO_PHONE);
        // 用 PasswordEncoder 现场加密，避免在 SQL 里硬编码 BCrypt 串
        user.setPassword(passwordEncoder.encode(DEMO_PASSWORD));
        user.setNickname("极客小张");
        user.setStatus(1);
        user.setLevelId(2L);
        user.setPoints(3200);
        user.setGrowth(1800);
        user.setGender(1);
        user.setEmail("demo@geekmall.com");
        user.setBio("这是一个演示账号");
        userMapper.insert(user);
        return user;
    }
}
