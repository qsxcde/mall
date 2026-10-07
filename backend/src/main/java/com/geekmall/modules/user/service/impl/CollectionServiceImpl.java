package com.geekmall.modules.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.modules.product.service.ProductService;
import com.geekmall.modules.product.vo.ProductCardVO;
import com.geekmall.modules.user.entity.UserFavorite;
import com.geekmall.modules.user.entity.UserHistory;
import com.geekmall.modules.user.mapper.UserFavoriteMapper;
import com.geekmall.modules.user.mapper.UserHistoryMapper;
import com.geekmall.modules.user.service.CollectionService;
import com.geekmall.modules.user.vo.HistoryItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 收藏 / 足迹服务实现。
 *
 * <p>商品详情统一通过 {@code ProductService.cardsByIds} 批量获取，
 * 既避免 N+1，也让用户域不直接依赖商品表。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionServiceImpl implements CollectionService {

    /** 足迹最多保留条数 */
    private static final int HISTORY_LIMIT = 50;

    private final UserFavoriteMapper userFavoriteMapper;
    private final UserHistoryMapper userHistoryMapper;
    private final ProductService productService;

    /* ------------------------------ 我的收藏 ------------------------------ */

    @Override
    public List<ProductCardVO> favorites(Long userId) {
        List<Long> productIds = userFavoriteMapper.selectList(new LambdaQueryWrapper<UserFavorite>()
                        .eq(UserFavorite::getUserId, userId)
                        .orderByDesc(UserFavorite::getId))
                .stream().map(UserFavorite::getProductId).toList();
        return productService.cardsByIds(productIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleFavorite(Long userId, Long productId) {
        UserFavorite exist = findFavorite(userId, productId);
        if (exist != null) {
            userFavoriteMapper.deleteById(exist.getId());
            return false;
        }
        UserFavorite favorite = new UserFavorite();
        favorite.setUserId(userId);
        favorite.setProductId(productId);
        try {
            userFavoriteMapper.insert(favorite);
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底并发重复收藏，结果同样是「已收藏」
            log.debug("并发重复收藏：user={}, product={}", userId, productId);
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeFavorite(Long userId, Long productId) {
        userFavoriteMapper.delete(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getProductId, productId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearFavorites(Long userId) {
        userFavoriteMapper.delete(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId));
    }

    /* ------------------------------ 浏览足迹 ------------------------------ */

    @Override
    public List<HistoryItemVO> history(Long userId) {
        List<UserHistory> records = userHistoryMapper.selectList(new LambdaQueryWrapper<UserHistory>()
                .eq(UserHistory::getUserId, userId)
                .orderByDesc(UserHistory::getViewTime)
                .orderByDesc(UserHistory::getId)
                .last("limit " + HISTORY_LIMIT));
        if (records.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductCardVO> cardMap = productService
                .cardsByIds(records.stream().map(UserHistory::getProductId).toList())
                .stream().collect(Collectors.toMap(ProductCardVO::getId, Function.identity(), (a, b) -> a));
        return records.stream()
                .map(record -> {
                    ProductCardVO card = cardMap.get(record.getProductId());
                    if (card == null) {
                        // 商品已下架/删除，跳过该条足迹
                        return null;
                    }
                    HistoryItemVO vo = new HistoryItemVO();
                    BeanUtils.copyProperties(card, vo);
                    vo.setViewTime(record.getViewTime());
                    return vo;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addHistory(Long userId, Long productId) {
        LocalDateTime now = LocalDateTime.now();
        UserHistory exist = findHistory(userId, productId);
        if (exist != null) {
            // 同一商品只保留一条，重复浏览只刷新时间
            UserHistory update = new UserHistory();
            update.setId(exist.getId());
            update.setViewTime(now);
            userHistoryMapper.updateById(update);
            return;
        }
        UserHistory history = new UserHistory();
        history.setUserId(userId);
        history.setProductId(productId);
        history.setViewTime(now);
        try {
            userHistoryMapper.insert(history);
        } catch (DuplicateKeyException e) {
            log.debug("并发重复记录足迹：user={}, product={}", userId, productId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeHistory(Long userId, Long productId) {
        userHistoryMapper.delete(new LambdaQueryWrapper<UserHistory>()
                .eq(UserHistory::getUserId, userId)
                .eq(UserHistory::getProductId, productId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearHistory(Long userId) {
        userHistoryMapper.delete(new LambdaQueryWrapper<UserHistory>()
                .eq(UserHistory::getUserId, userId));
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private UserFavorite findFavorite(Long userId, Long productId) {
        return userFavoriteMapper.selectOne(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getProductId, productId)
                .last("limit 1"));
    }

    private UserHistory findHistory(Long userId, Long productId) {
        return userHistoryMapper.selectOne(new LambdaQueryWrapper<UserHistory>()
                .eq(UserHistory::getUserId, userId)
                .eq(UserHistory::getProductId, productId)
                .last("limit 1"));
    }
}
