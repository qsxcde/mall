package com.geekmall.modules.user.service;

import com.geekmall.modules.user.vo.MemberInfoVO;

/**
 * 会员服务。
 */
public interface MemberService {

    /** 会员中心信息：等级、成长进度、权益、等级阶梯、签到状态。 */
    MemberInfoVO info(Long userId);
}
