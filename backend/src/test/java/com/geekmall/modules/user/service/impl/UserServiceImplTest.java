package com.geekmall.modules.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.geekmall.common.exception.BizException;
import com.geekmall.modules.user.dto.AddressDTO;
import com.geekmall.modules.user.dto.ProfileUpdateDTO;
import com.geekmall.modules.user.entity.SysUser;
import com.geekmall.modules.user.entity.UserAddress;
import com.geekmall.modules.user.entity.UserSign;
import com.geekmall.modules.user.mapper.SysUserMapper;
import com.geekmall.modules.user.mapper.UserAddressMapper;
import com.geekmall.modules.user.mapper.UserSignMapper;
import com.geekmall.modules.user.vo.SignVO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 用户中心服务单元测试。
 *
 * <p>重点守住：<b>地址越权</b>（改 / 删他人地址必须 404）、<b>默认地址不变量</b>
 * （任意时刻至多一条、删默认后必须补位）、<b>签到幂等</b>（当日重复签到不重复加分）与
 * <b>积分条件扣减</b>（不能扣成负数）。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final Long USER_ID = 42L;

    @Mock
    private SysUserMapper userMapper;

    @Mock
    private UserAddressMapper addressMapper;

    @Mock
    private UserSignMapper signMapper;

    @InjectMocks
    private UserServiceImpl userService;

    private static SysUser user(Long id, Integer points) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setNickname("测试用户");
        user.setPoints(points);
        return user;
    }

    private static UserAddress address(long id, long userId, boolean isDefault) {
        UserAddress address = new UserAddress();
        address.setId(id);
        address.setUserId(userId);
        address.setName("张三");
        address.setPhone("13800000000");
        address.setIsDefault(isDefault ? 1 : 0);
        return address;
    }

    @Nested
    @DisplayName("资料与积分")
    class ProfileAndPoints {

        @Test
        @DisplayName("用户不存在 → USER_NOT_FOUND")
        void shouldRejectMissingUser() {
            when(userMapper.selectById(USER_ID)).thenReturn(null);
            assertThatThrownBy(() -> userService.getProfile(USER_ID))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("用户不存在");
        }

        @Test
        @DisplayName("资料更新：avatar 为空时不清空原头像（null 字段局部更新）")
        void blankAvatarShouldNotOverwrite() {
            when(userMapper.selectById(USER_ID)).thenReturn(user(USER_ID, 10));

            ProfileUpdateDTO dto = new ProfileUpdateDTO();
            dto.setNickname("新昵称");
            dto.setAvatar(" ");
            userService.updateProfile(USER_ID, dto);

            ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
            verify(userMapper).updateById(captor.capture());
            assertThat(captor.getValue().getAvatar()).isNull();
            assertThat(captor.getValue().getNickname()).isEqualTo("新昵称");
        }

        @Test
        @DisplayName("积分条件扣减：非正数入参拒绝，余额不足拒绝（不能扣成负数）")
        void deductPointsShouldNeverGoNegative() {
            assertThatThrownBy(() -> userService.deductPoints(USER_ID, 0))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("正数");
            assertThatThrownBy(() -> userService.deductPoints(USER_ID, -1)).isInstanceOf(BizException.class);

            when(userMapper.deductPoints(USER_ID, 30)).thenReturn(0);
            assertThatThrownBy(() -> userService.deductPoints(USER_ID, 30))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("积分不足");
        }

        @Test
        @DisplayName("条件扣减成功 / tryDeductPoints 透传结果")
        void deductPointsSuccess() {
            when(userMapper.deductPoints(USER_ID, 30)).thenReturn(1);
            userService.deductPoints(USER_ID, 30);

            when(userMapper.deductPoints(USER_ID, 5)).thenReturn(0);
            assertThat(userService.tryDeductPoints(USER_ID, 5)).isFalse();
            when(userMapper.deductPoints(USER_ID, 5)).thenReturn(1);
            assertThat(userService.tryDeductPoints(USER_ID, 5)).isTrue();
        }

        @Test
        @DisplayName("nicknames：空集合返回空 map，空昵称归一为空串")
        void nicknamesNormalizeBlank() {
            assertThat(userService.nicknames(List.of())).isEqualTo(Map.of());

            SysUser noNickname = user(1L, 0);
            noNickname.setNickname(null);
            when(userMapper.selectBatchIds(any())).thenReturn(List.of(noNickname));
            assertThat(userService.nicknames(List.of(1L))).containsEntry(1L, "");

            when(userMapper.selectCount(null)).thenReturn(null);
            assertThat(userService.countUsers()).isZero();
        }
    }

    @Nested
    @DisplayName("收货地址（含越权守卫）")
    class Address {

        @Test
        @DisplayName("操作他人地址 → 404（查 / 改 / 删 / 设默认同口径）")
        void shouldRejectForeignAddress() {
            when(addressMapper.selectById(5L)).thenReturn(address(5L, 999L, false));

            AddressDTO dto = new AddressDTO();
            assertThatThrownBy(() -> userService.getAddress(USER_ID, 5L)).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> userService.updateAddress(USER_ID, 5L, dto))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> userService.deleteAddress(USER_ID, 5L)).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> userService.setDefaultAddress(USER_ID, 5L)).isInstanceOf(BizException.class);
            verify(addressMapper, never()).updateById(any(UserAddress.class));
            verify(addressMapper, never()).deleteById(anyLong());
        }

        @Test
        @DisplayName("首条地址自动成为默认地址")
        void firstAddressBecomesDefault() {
            when(addressMapper.selectCount(any())).thenReturn(0L);

            AddressDTO dto = new AddressDTO();
            dto.setName("张三");
            userService.addAddress(USER_ID, dto);

            ArgumentCaptor<UserAddress> captor = ArgumentCaptor.forClass(UserAddress.class);
            verify(addressMapper).insert(captor.capture());
            assertThat(captor.getValue().getIsDefault()).isEqualTo(1);
        }

        @Test
        @DisplayName("新默认地址会先清掉旧默认（不变量：至多一条默认）")
        void settingDefaultClearsPrevious() {
            when(addressMapper.selectById(5L)).thenReturn(address(5L, USER_ID, false));

            AddressDTO dto = new AddressDTO();
            dto.setIsDefault(true);
            userService.updateAddress(USER_ID, 5L, dto);

            verify(addressMapper).update(any(), any());
            ArgumentCaptor<UserAddress> captor = ArgumentCaptor.forClass(UserAddress.class);
            verify(addressMapper).updateById(captor.capture());
            assertThat(captor.getValue().getIsDefault()).isEqualTo(1);
        }

        @Test
        @DisplayName("删除默认地址后把剩余最新一条补位为默认")
        void deletingDefaultPromotesNext() {
            when(addressMapper.selectById(5L)).thenReturn(address(5L, USER_ID, true));
            when(addressMapper.selectOne(any())).thenReturn(address(6L, USER_ID, false));

            userService.deleteAddress(USER_ID, 5L);

            verify(addressMapper).deleteById(5L);
            ArgumentCaptor<UserAddress> captor = ArgumentCaptor.forClass(UserAddress.class);
            verify(addressMapper).updateById(captor.capture());
            assertThat(captor.getValue().getId()).isEqualTo(6L);
            assertThat(captor.getValue().getIsDefault()).isEqualTo(1);
        }

        @Test
        @DisplayName("删掉最后一条地址时不产生补位更新")
        void deletingLastAddressSkipsPromotion() {
            when(addressMapper.selectById(5L)).thenReturn(address(5L, USER_ID, true));
            when(addressMapper.selectOne(any())).thenReturn(null);

            userService.deleteAddress(USER_ID, 5L);

            verify(addressMapper).deleteById(5L);
            verify(addressMapper, never()).updateById(any(UserAddress.class));
        }
    }

    @Nested
    @DisplayName("签到")
    class SignIn {

        @Test
        @DisplayName("当日重复签到幂等：不重复加分，返回 gained=0")
        void signInIsIdempotentPerDay() {
            when(userMapper.selectById(USER_ID)).thenReturn(user(USER_ID, 30));
            when(signMapper.selectCount(any())).thenReturn(1L);

            SignVO vo = userService.signIn(USER_ID);

            verify(userMapper, never()).addPoints(any(), anyInt());
            verify(signMapper, never()).insert(any(UserSign.class));
            assertThat(vo.getSigned()).isFalse();
            assertThat(vo.getGainedPoints()).isZero();
            assertThat(vo.getSignedToday()).isTrue();
            assertThat(vo.getTotalPoints()).isEqualTo(30);
        }

        @Test
        @DisplayName("首次签到：记签到流水 + 原子自增加分（并发签到不丢分）")
        void firstSignInGrantsPoints() {
            when(userMapper.selectById(USER_ID)).thenReturn(user(USER_ID, 30));
            when(signMapper.selectCount(any())).thenReturn(0L, 5L, 1L);

            SignVO vo = userService.signIn(USER_ID);

            verify(signMapper).insert(any(UserSign.class));
            verify(userMapper).addPoints(USER_ID, 10);
            assertThat(vo.getSigned()).isTrue();
            assertThat(vo.getGainedPoints()).isEqualTo(10);
            assertThat(vo.getSignDays()).isEqualTo(5);
        }
    }
}
