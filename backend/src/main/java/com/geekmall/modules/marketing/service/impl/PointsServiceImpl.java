package com.geekmall.modules.marketing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.marketing.entity.PointsExchange;
import com.geekmall.modules.marketing.entity.PointsGoods;
import com.geekmall.modules.marketing.mapper.PointsExchangeMapper;
import com.geekmall.modules.marketing.mapper.PointsGoodsMapper;
import com.geekmall.modules.marketing.service.PointsService;
import com.geekmall.modules.marketing.vo.PointsExchangeVO;
import com.geekmall.modules.marketing.vo.PointsGoodsVO;
import com.geekmall.modules.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 积分商城服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsServiceImpl implements PointsService {

    private final PointsGoodsMapper pointsGoodsMapper;
    private final PointsExchangeMapper pointsExchangeMapper;
    private final UserService userService;

    @Override
    public List<PointsGoodsVO> goods(Long userId) {
        int myPoints = userService.getPoints(userId);
        return pointsGoodsMapper.selectList(new LambdaQueryWrapper<PointsGoods>()
                        .orderByAsc(PointsGoods::getPoints)
                        .orderByAsc(PointsGoods::getId))
                .stream().map(goods -> toVO(goods, myPoints)).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PointsExchangeVO exchange(Long userId, Long goodsId) {
        PointsGoods goods = pointsGoodsMapper.selectById(goodsId);
        if (goods == null) {
            throw new BizException(ResultCode.NOT_FOUND, "积分商品不存在");
        }
        if (goods.getStock() == null || goods.getStock() <= 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "该商品已兑完");
        }
        // 先扣库存（带 stock > 0 条件），再扣积分；任一步失败整体回滚
        if (pointsGoodsMapper.deductStock(goodsId) == 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "该商品已兑完");
        }
        int cost = goods.getPoints() == null ? 0 : goods.getPoints();
        if (cost > 0) {
            // 内部带 points >= n 条件，积分不足会抛异常并回滚上面的库存扣减
            userService.deductPoints(userId, cost);
        }

        PointsExchange record = new PointsExchange();
        record.setUserId(userId);
        record.setGoodsId(goodsId);
        record.setGoodsName(goods.getName());
        record.setPoints(cost);
        pointsExchangeMapper.insert(record);

        int remain = userService.getPoints(userId);
        log.info("用户 {} 兑换积分商品「{}」，消耗 {} 积分，剩余 {}",
                userId, goods.getName(), cost, remain);
        return new PointsExchangeVO(goodsId, goods.getName(), cost, remain, record.getCreateTime());
    }

    @Override
    public List<PointsExchangeVO> records(Long userId) {
        return pointsExchangeMapper.selectList(new LambdaQueryWrapper<PointsExchange>()
                        .eq(PointsExchange::getUserId, userId)
                        .orderByDesc(PointsExchange::getId))
                .stream()
                .map(record -> new PointsExchangeVO(record.getGoodsId(), record.getGoodsName(),
                        record.getPoints(), null, record.getCreateTime()))
                .toList();
    }

    private PointsGoodsVO toVO(PointsGoods goods, int myPoints) {
        PointsGoodsVO vo = new PointsGoodsVO();
        vo.setId(goods.getId());
        vo.setName(goods.getName());
        vo.setPoints(goods.getPoints());
        vo.setIcon(goods.getIcon());
        vo.setCategory(goods.getCategory());
        vo.setDescription(goods.getDescription());
        vo.setStock(goods.getStock());
        vo.setSoldout(goods.getStock() == null || goods.getStock() <= 0);
        vo.setAffordable(myPoints >= (goods.getPoints() == null ? 0 : goods.getPoints()));
        return vo;
    }
}
