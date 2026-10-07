package com.geekmall.modules.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.user.entity.MemberLevel;
import com.geekmall.modules.user.entity.SysUser;
import com.geekmall.modules.user.entity.UserSign;
import com.geekmall.modules.user.mapper.MemberLevelMapper;
import com.geekmall.modules.user.mapper.SysUserMapper;
import com.geekmall.modules.user.mapper.UserSignMapper;
import com.geekmall.modules.user.service.MemberService;
import com.geekmall.modules.user.vo.MemberInfoVO;
import com.geekmall.modules.user.vo.MemberTierVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * 会员服务实现。
 *
 * <p>等级不落库冗余，而是按 user.growth 在等级表里实时匹配——
 * 等级规则调整后无需刷数据，代价只是一次很小的全表查询（等级通常只有几条）。</p>
 */
@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    private static final String[] TIER_ICONS = {"🥉", "🥈", "🥇", "💎", "👑"};

    private final SysUserMapper userMapper;
    private final MemberLevelMapper memberLevelMapper;
    private final UserSignMapper userSignMapper;

    @Override
    public MemberInfoVO info(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ResultCode.USER_NOT_FOUND);
        }
        List<MemberLevel> levels = memberLevelMapper.selectList(new LambdaQueryWrapper<MemberLevel>()
                .orderByAsc(MemberLevel::getGrowthMin));

        int growth = nvl(user.getGrowth());
        MemberLevel current = levels.stream()
                .filter(level -> growth >= nvl(level.getGrowthMin()) && growth <= nvl(level.getGrowthMax()))
                .findFirst()
                .orElse(levels.isEmpty() ? null : levels.get(0));
        MemberLevel next = current == null ? null : levels.stream()
                .filter(level -> nvl(level.getGrowthMin()) > nvl(current.getGrowthMin()))
                .findFirst()
                .orElse(null);

        MemberInfoVO vo = new MemberInfoVO();
        vo.setNickname(user.getNickname());
        vo.setPoints(nvl(user.getPoints()));
        vo.setGrowth(growth);
        vo.setLevelId(user.getLevelId());
        vo.setBenefits(List.of());

        if (current != null) {
            int min = nvl(current.getGrowthMin());
            int max = nvl(current.getGrowthMax());
            int span = max - min;
            vo.setLevelId(current.getId());
            vo.setLevelName(current.getLevelName());
            vo.setGrowthMin(min);
            vo.setGrowthMax(max);
            vo.setDiscountRate(current.getDiscountRate());
            vo.setBenefits(splitBenefits(current.getBenefits()));
            vo.setGrowthPercent(span <= 0 ? 100 : Math.min(100, Math.max(0, (growth - min) * 100 / span)));
        }
        if (next != null) {
            vo.setNextLevelName(next.getLevelName());
            vo.setNextLevelGrowth(nvl(next.getGrowthMin()));
        }
        vo.setTiers(buildTiers(levels, current));
        vo.setSignedToday(hasSignedToday(userId));
        vo.setSignDays(countSignDays(userId));
        return vo;
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private List<MemberTierVO> buildTiers(List<MemberLevel> levels, MemberLevel current) {
        int[] index = {0};
        return levels.stream().map(level -> {
            int min = nvl(level.getGrowthMin());
            String req = min <= 0 ? "注册即享" : "成长值 ≥ " + min;
            String perk = String.join(" · ", splitBenefits(level.getBenefits()));
            String icon = TIER_ICONS[Math.min(index[0]++, TIER_ICONS.length - 1)];
            return new MemberTierVO(icon, level.getLevelName(), req, perk,
                    current != null && current.getId().equals(level.getId()));
        }).toList();
    }

    private boolean hasSignedToday(Long userId) {
        Long count = userSignMapper.selectCount(new LambdaQueryWrapper<UserSign>()
                .eq(UserSign::getUserId, userId)
                .eq(UserSign::getSignDate, LocalDate.now()));
        return count != null && count > 0;
    }

    private int countSignDays(Long userId) {
        Long count = userSignMapper.selectCount(new LambdaQueryWrapper<UserSign>()
                .eq(UserSign::getUserId, userId));
        return count == null ? 0 : count.intValue();
    }

    private List<String> splitBenefits(String benefits) {
        if (!StringUtils.hasText(benefits)) {
            return List.of();
        }
        return Arrays.stream(benefits.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private int nvl(Integer value) {
        return value == null ? 0 : value;
    }
}
