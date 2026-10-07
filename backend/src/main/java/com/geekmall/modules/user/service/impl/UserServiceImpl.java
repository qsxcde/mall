package com.geekmall.modules.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.user.converter.AddressConverter;
import com.geekmall.modules.user.converter.UserConverter;
import com.geekmall.modules.user.dto.AddressDTO;
import com.geekmall.modules.user.dto.ProfileUpdateDTO;
import com.geekmall.modules.user.entity.SysUser;
import com.geekmall.modules.user.entity.UserAddress;
import com.geekmall.modules.user.entity.UserSign;
import com.geekmall.modules.user.mapper.SysUserMapper;
import com.geekmall.modules.user.mapper.UserAddressMapper;
import com.geekmall.modules.user.mapper.UserSignMapper;
import com.geekmall.modules.user.service.UserService;
import com.geekmall.modules.user.vo.AddressVO;
import com.geekmall.modules.user.vo.SignVO;
import com.geekmall.modules.user.vo.UserProfileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户中心服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /** 每日签到基础积分 */
    private static final int SIGN_POINTS = 10;

    private final SysUserMapper userMapper;
    private final UserAddressMapper addressMapper;
    private final UserSignMapper signMapper;

    @Override
    public UserProfileVO getProfile(Long userId) {
        return UserConverter.toProfile(requireUser(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(Long userId, ProfileUpdateDTO dto) {
        requireUser(userId);
        SysUser update = new SysUser();
        update.setId(userId);
        update.setNickname(dto.getNickname());
        update.setGender(dto.getGender());
        update.setBirthday(dto.getBirthday());
        update.setEmail(dto.getEmail());
        update.setBio(dto.getBio());
        if (StringUtils.hasText(dto.getAvatar())) {
            update.setAvatar(dto.getAvatar());
        }
        // MyBatis-Plus 默认忽略 null 字段，适合“局部更新”
        userMapper.updateById(update);
    }

    /* ------------------------------ 收货地址 ------------------------------ */

    @Override
    public List<AddressVO> listAddresses(Long userId) {
        List<UserAddress> addresses = addressMapper.selectList(new LambdaQueryWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId)
                .orderByDesc(UserAddress::getIsDefault)
                .orderByDesc(UserAddress::getId));
        return addresses.stream().map(AddressConverter::toVO).toList();
    }

    @Override
    public AddressVO getAddress(Long userId, Long addressId) {
        return AddressConverter.toVO(requireOwnAddress(userId, addressId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addAddress(Long userId, AddressDTO dto) {
        long total = addressMapper.selectCount(new LambdaQueryWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId));
        // 第一条地址自动成为默认地址
        boolean asDefault = Boolean.TRUE.equals(dto.getIsDefault()) || total == 0;
        if (asDefault) {
            clearDefault(userId);
        }
        UserAddress entity = new UserAddress();
        entity.setUserId(userId);
        entity.setName(dto.getName());
        entity.setPhone(dto.getPhone());
        entity.setProvince(dto.getProvince());
        entity.setCity(dto.getCity());
        entity.setDistrict(dto.getDistrict());
        entity.setDetail(dto.getDetail());
        entity.setIsDefault(asDefault ? 1 : 0);
        addressMapper.insert(entity);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAddress(Long userId, Long addressId, AddressDTO dto) {
        requireOwnAddress(userId, addressId);
        if (Boolean.TRUE.equals(dto.getIsDefault())) {
            clearDefault(userId);
        }
        UserAddress update = new UserAddress();
        update.setId(addressId);
        update.setName(dto.getName());
        update.setPhone(dto.getPhone());
        update.setProvince(dto.getProvince());
        update.setCity(dto.getCity());
        update.setDistrict(dto.getDistrict());
        update.setDetail(dto.getDetail());
        update.setIsDefault(Boolean.TRUE.equals(dto.getIsDefault()) ? 1 : 0);
        addressMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAddress(Long userId, Long addressId) {
        UserAddress address = requireOwnAddress(userId, addressId);
        addressMapper.deleteById(addressId);
        // 删掉的是默认地址时，把剩下最新的一条置为默认
        if (address.getIsDefault() != null && address.getIsDefault() == 1) {
            UserAddress next = addressMapper.selectOne(new LambdaQueryWrapper<UserAddress>()
                    .eq(UserAddress::getUserId, userId)
                    .orderByDesc(UserAddress::getId)
                    .last("limit 1"));
            if (next != null) {
                UserAddress update = new UserAddress();
                update.setId(next.getId());
                update.setIsDefault(1);
                addressMapper.updateById(update);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefaultAddress(Long userId, Long addressId) {
        requireOwnAddress(userId, addressId);
        clearDefault(userId);
        UserAddress update = new UserAddress();
        update.setId(addressId);
        update.setIsDefault(1);
        addressMapper.updateById(update);
    }

    /* ------------------------------ 积分签到 ------------------------------ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SignVO signIn(Long userId) {
        LocalDate today = LocalDate.now();
        Long signedCount = signMapper.selectCount(new LambdaQueryWrapper<UserSign>()
                .eq(UserSign::getUserId, userId)
                .eq(UserSign::getSignDate, today));
        if (signedCount != null && signedCount > 0) {
            // 幂等：重复签到不报错，直接返回当前状态
            return buildSignVO(userId, 0, false);
        }

        UserSign sign = new UserSign();
        sign.setUserId(userId);
        sign.setSignDate(today);
        sign.setPoints(SIGN_POINTS);
        signMapper.insert(sign);

        // 用原子自增代替「读-改-写」，避免并发签到丢分
        userMapper.addPoints(userId, SIGN_POINTS);
        log.info("用户 {} 签到成功，获得 {} 积分", userId, SIGN_POINTS);
        return buildSignVO(userId, SIGN_POINTS, true);
    }

    @Override
    public SignVO signStatus(Long userId) {
        return buildSignVO(userId, 0, false);
    }

    /* ------------------------------ 积分 ------------------------------ */

    @Override
    public int getPoints(Long userId) {
        return nvl(requireUser(userId).getPoints());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deductPoints(Long userId, int points) {
        if (points <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "扣减积分必须为正数");
        }
        // 带 points >= n 条件，避免并发下扣成负数
        if (userMapper.deductPoints(userId, points) == 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "积分不足");
        }
    }

    @Override
    public Map<Long, String> nicknames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(SysUser::getId,
                        user -> user.getNickname() == null ? "" : user.getNickname(),
                        (a, b) -> a));
    }

    @Override
    public long countUsers() {
        Long count = userMapper.selectCount(null);
        return count == null ? 0L : count;
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private SignVO buildSignVO(Long userId, int gained, boolean signedNow) {
        SysUser user = requireUser(userId);
        Long days = signMapper.selectCount(new LambdaQueryWrapper<UserSign>().eq(UserSign::getUserId, userId));
        Long todayCount = signMapper.selectCount(new LambdaQueryWrapper<UserSign>()
                .eq(UserSign::getUserId, userId)
                .eq(UserSign::getSignDate, LocalDate.now()));
        return new SignVO(signedNow, todayCount != null && todayCount > 0, gained,
                nvl(user.getPoints()), days == null ? 0 : days.intValue());
    }

    private void clearDefault(Long userId) {
        UserAddress update = new UserAddress();
        update.setIsDefault(0);
        addressMapper.update(update, new LambdaUpdateWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId));
    }

    private UserAddress requireOwnAddress(Long userId, Long addressId) {
        UserAddress address = addressMapper.selectById(addressId);
        if (address == null || !address.getUserId().equals(userId)) {
            throw BizException.of(ResultCode.NOT_FOUND);
        }
        return address;
    }

    private SysUser requireUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ResultCode.USER_NOT_FOUND);
        }
        return user;
    }

    private int nvl(Integer value) {
        return value == null ? 0 : value;
    }
}
