package com.geekmall.modules.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.user.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户 Mapper。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 原子扣减积分：带 {@code points >= n} 条件，并发下不会扣成负数。
     * 返回 0 表示积分不足。
     */
    @Update("UPDATE sys_user SET points = points - #{points} WHERE id = #{id} AND points >= #{points} AND deleted = 0")
    int deductPoints(@Param("id") Long id, @Param("points") int points);

    /** 原子增加积分与成长值（签到、下单奖励等）。 */
    @Update("UPDATE sys_user SET points = points + #{points}, growth = growth + #{points} "
            + "WHERE id = #{id} AND deleted = 0")
    int addPoints(@Param("id") Long id, @Param("points") int points);
}
