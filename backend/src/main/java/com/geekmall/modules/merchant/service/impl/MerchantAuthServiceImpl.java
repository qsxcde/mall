package com.geekmall.modules.merchant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.constant.RedisKeys;
import com.geekmall.common.constant.SecurityConstants;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.common.util.JwtUtil;
import com.geekmall.modules.merchant.converter.MerchantConverter;
import com.geekmall.modules.merchant.dto.MerchantLoginDTO;
import com.geekmall.modules.merchant.entity.MerchantUser;
import com.geekmall.modules.merchant.entity.Shop;
import com.geekmall.modules.merchant.mapper.MerchantUserMapper;
import com.geekmall.modules.merchant.mapper.ShopMapper;
import com.geekmall.modules.merchant.service.MerchantAuthService;
import com.geekmall.modules.merchant.vo.MerchantLoginVO;
import com.geekmall.modules.merchant.vo.MerchantProfileVO;
import com.geekmall.modules.merchant.vo.ShopVO;
import com.geekmall.security.MerchantLoginUser;
import com.geekmall.security.MerchantSecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 商家端认证服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantAuthServiceImpl implements MerchantAuthService {

    private final MerchantUserMapper merchantUserMapper;
    private final ShopMapper shopMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    @Override
    public MerchantLoginVO login(MerchantLoginDTO dto) {
        MerchantUser user = merchantUserMapper.selectOne(new LambdaQueryWrapper<MerchantUser>()
                .eq(MerchantUser::getUsername, dto.getAccount())
                .last("limit 1"));
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw BizException.of(ResultCode.PASSWORD_ERROR);
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw BizException.of(ResultCode.ACCOUNT_DISABLED);
        }
        Shop shop = shopMapper.selectById(user.getShopId());
        if (shop == null || (shop.getStatus() != null && shop.getStatus() == 0)) {
            throw new BizException(ResultCode.FORBIDDEN, "店铺已停业，请联系平台");
        }

        String token = jwtUtil.createToken(user.getId(), user.getUsername(),
                SecurityConstants.SCOPE_MERCHANT, user.getShopId());
        // 与服务端会话绑定，支持登出与单点登录踢下线
        redisTemplate.opsForValue().set(RedisKeys.merchantLoginToken(user.getId()), token,
                jwtUtil.getExpireSeconds(), TimeUnit.SECONDS);
        return new MerchantLoginVO(token, "Bearer", jwtUtil.getExpireSeconds(),
                MerchantConverter.toShopVO(shop), MerchantConverter.toProfile(user, shop));
    }

    @Override
    public ShopVO currentShop() {
        Long shopId = MerchantSecurityUtils.getShopId();
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            throw BizException.of(ResultCode.NOT_FOUND);
        }
        return MerchantConverter.toShopVO(shop);
    }

    @Override
    public MerchantProfileVO currentProfile() {
        MerchantLoginUser login = MerchantSecurityUtils.getLoginUser();
        MerchantUser user = merchantUserMapper.selectById(login.getMerchantUserId());
        Shop shop = shopMapper.selectById(login.getShopId());
        return MerchantConverter.toProfile(user, shop);
    }

    @Override
    public void logout() {
        try {
            Long merchantUserId = MerchantSecurityUtils.getMerchantUserId();
            redisTemplate.delete(RedisKeys.merchantLoginToken(merchantUserId));
        } catch (Exception e) {
            log.debug("商家登出时未获取到登录态，忽略：{}", e.getMessage());
        }
    }
}
