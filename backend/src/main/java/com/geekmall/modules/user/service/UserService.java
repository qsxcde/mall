package com.geekmall.modules.user.service;

import com.geekmall.modules.user.dto.AddressDTO;
import com.geekmall.modules.user.dto.ProfileUpdateDTO;
import com.geekmall.modules.user.vo.AddressVO;
import com.geekmall.modules.user.vo.SignVO;
import com.geekmall.modules.user.vo.UserProfileVO;

import java.util.List;

/**
 * 用户中心服务：资料、收货地址、积分签到。
 */
public interface UserService {

    UserProfileVO getProfile(Long userId);

    void updateProfile(Long userId, ProfileUpdateDTO dto);

    List<AddressVO> listAddresses(Long userId);

    /** 获取归属该用户的地址，不存在或越权时抛异常（供交易域下单使用）。 */
    AddressVO getAddress(Long userId, Long addressId);

    Long addAddress(Long userId, AddressDTO dto);

    void updateAddress(Long userId, Long addressId, AddressDTO dto);

    void deleteAddress(Long userId, Long addressId);

    void setDefaultAddress(Long userId, Long addressId);

    /** 每日签到：成功加分，重复签到返回当前状态。 */
    SignVO signIn(Long userId);

    /** 查询今日签到状态。 */
    SignVO signStatus(Long userId);

    /** 查询可用积分。 */
    int getPoints(Long userId);

    /** 原子扣减积分，不足时抛业务异常（供积分商城等消费场景）。 */
    void deductPoints(Long userId, int points);

    /** 批量查询昵称，供评价列表等场景展示（避免跨模块直接访问用户表）。 */
    java.util.Map<Long, String> nicknames(java.util.Collection<Long> userIds);

    /** 注册用户数（供内容域统计展示）。 */
    long countUsers();
}
