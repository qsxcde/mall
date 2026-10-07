package com.geekmall.modules.user.service;

import com.geekmall.modules.product.vo.ProductCardVO;
import com.geekmall.modules.user.vo.HistoryItemVO;

import java.util.List;

/**
 * 我的收藏 / 浏览足迹服务。
 */
public interface CollectionService {

    /** 收藏列表（按收藏时间倒序）。 */
    List<ProductCardVO> favorites(Long userId);

    /**
     * 收藏 / 取消收藏。
     *
     * @return true 表示收藏成功，false 表示已取消收藏
     */
    boolean toggleFavorite(Long userId, Long productId);

    void removeFavorite(Long userId, Long productId);

    void clearFavorites(Long userId);

    /** 浏览足迹（按浏览时间倒序，最多 50 条）。 */
    List<HistoryItemVO> history(Long userId);

    /** 记录浏览：同商品重复浏览只更新时间，不新增记录。 */
    void addHistory(Long userId, Long productId);

    void removeHistory(Long userId, Long productId);

    void clearHistory(Long userId);
}
