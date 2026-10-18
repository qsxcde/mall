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

    /**
     * 批量查询用户实体（{@code id -> SysUser}），供商家端订单/售后/评价/发货装配买家信息。
     *
     * <p>与 {@link #nicknames} 的分工：只需要展示昵称时用前者（更轻）；
     * 需要手机号、头像等多个字段时用后者，避免上层反复发起多次查询。</p>
     *
     * <p>返回的 Map <b>不保证</b>包含所有传入 id（用户可能已被删除），调用方需处理缺省。</p>
     */
    java.util.Map<Long, com.geekmall.modules.user.entity.SysUser> usersByIds(java.util.Collection<Long> userIds);

    /** 注册用户数（供内容域统计展示）。 */
    long countUsers();

    // ------------------------------------------------------------------
    // 认证域所需：账户读取与凭据写入
    // ------------------------------------------------------------------

    /**
     * 按「用户名或手机号」查账户（登录 / 找回密码用）。
     *
     * @return 不存在时返回 {@code null}
     */
    com.geekmall.modules.user.entity.SysUser findByAccount(String account);

    /** 新建账户（注册用，调用方已完成密码加密与默认值填充）。 */
    void createAccount(com.geekmall.modules.user.entity.SysUser user);

    /** 更新昵称（注册后补充资料）。 */
    void updateNickname(Long userId, String nickname);

    /** 更新密码（调用方已完成加密）。 */
    void updatePassword(Long userId, String encodedPassword);

    /**
     * 原子扣减积分，不足时抛业务异常。
     *
     * <p>与 {@link #deductPoints} 的区别：本方法返回是否成功而非抛异常，
     * 供「先判断再扣」的场景避免异常开销。</p>
     */
    boolean tryDeductPoints(Long userId, int points);
}
