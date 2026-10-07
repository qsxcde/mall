package com.geekmall.modules.user.converter;

import com.geekmall.modules.user.entity.SysUser;
import com.geekmall.modules.user.vo.UserProfileVO;

/**
 * 用户对象转换。
 *
 * <p>当字段映射变多时可替换为 MapStruct，这里手写以保证零配置可运行。</p>
 */
public final class UserConverter {

    private UserConverter() {
    }

    public static UserProfileVO toProfile(SysUser user) {
        if (user == null) {
            return null;
        }
        UserProfileVO vo = new UserProfileVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setPhone(user.getPhone());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setGender(user.getGender());
        vo.setBirthday(user.getBirthday());
        vo.setEmail(user.getEmail());
        vo.setBio(user.getBio());
        vo.setLevelId(user.getLevelId());
        vo.setPoints(user.getPoints());
        vo.setGrowth(user.getGrowth());
        return vo;
    }
}
