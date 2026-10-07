package com.geekmall.modules.review.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.geekmall.common.enums.OrderStatus;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.PageResult;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.review.dto.SubmitReviewDTO;
import com.geekmall.modules.review.entity.Review;
import com.geekmall.modules.review.mapper.ReviewMapper;
import com.geekmall.modules.review.service.ReviewService;
import com.geekmall.modules.review.vo.ReviewVO;
import com.geekmall.modules.trade.service.OrderService;
import com.geekmall.modules.trade.service.TradeService;
import com.geekmall.modules.trade.vo.OrderDetailVO;
import com.geekmall.modules.trade.vo.OrderItemVO;
import com.geekmall.modules.trade.vo.OrderVO;
import com.geekmall.modules.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 评价服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private static final String ANONYMOUS_NAME = "匿名用户";
    private static final String DEFAULT_NAME = "极客用户";

    private final ReviewMapper reviewMapper;
    private final OrderService orderService;
    private final TradeService tradeService;
    private final ProductMapper productMapper;
    private final UserService userService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int submit(Long userId, SubmitReviewDTO dto) {
        // 借用交易域的详情：顺带完成「订单归属」与「状态」两道校验，避免重复实现
        OrderDetailVO detail = orderService.detail(userId, dto.getOrderNo());
        OrderVO order = detail.getOrder();
        if (order.getStatus() == null || order.getStatus() != OrderStatus.PENDING_COMMENT.getCode()) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "当前订单为「" + order.getStatusText() + "」，不可评价");
        }
        Long reviewed = reviewMapper.selectCount(new LambdaQueryWrapper<Review>()
                .eq(Review::getOrderNo, dto.getOrderNo()));
        if (reviewed != null && reviewed > 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "该订单已经评价过啦");
        }

        List<OrderItemVO> items = order.getItems();
        if (items == null || items.isEmpty()) {
            throw new BizException(ResultCode.BIZ_ERROR, "订单没有可评价的商品");
        }
        String images = (dto.getImages() == null || dto.getImages().isEmpty())
                ? null : String.join(",", dto.getImages());

        try {
            for (OrderItemVO item : items) {
                Review review = new Review();
                review.setUserId(userId);
                review.setOrderNo(dto.getOrderNo());
                review.setProductId(item.getProductId());
                review.setScoreDesc(dto.getScoreDesc());
                review.setScoreLogistics(dto.getScoreLogistics());
                review.setScoreService(dto.getScoreService());
                review.setContent(dto.getContent());
                review.setImages(images);
                review.setAnonymous(Boolean.TRUE.equals(dto.getAnonymous()) ? 1 : 0);
                reviewMapper.insert(review);
            }
        } catch (DuplicateKeyException e) {
            // P2-2：唯一索引 uk_user_order_product 兜底，并发重复提交时给出友好提示
            throw new BizException(ResultCode.BIZ_ERROR, "该订单已经评价过啦");
        }

        // 评价完成 → 订单从「待评价」流转到「已完成」，状态机收尾
        tradeService.markReviewed(userId, dto.getOrderNo());
        log.info("用户 {} 完成订单 {} 的评价，共 {} 件商品", userId, dto.getOrderNo(), items.size());
        return items.size();
    }

    @Override
    public List<ReviewVO> myReviews(Long userId) {
        List<Review> reviews = reviewMapper.selectList(new LambdaQueryWrapper<Review>()
                .eq(Review::getUserId, userId)
                .orderByDesc(Review::getId));
        return toVOList(reviews);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long reviewId) {
        Review review = reviewMapper.selectById(reviewId);
        if (review == null || !review.getUserId().equals(userId)) {
            throw new BizException(ResultCode.NOT_FOUND, "评价不存在");
        }
        reviewMapper.deleteById(reviewId);
    }

    @Override
    public PageResult<ReviewVO> productReviews(Long productId, int page, int pageSize) {
        Page<Review> pageParam = new Page<>(page, pageSize);
        IPage<Review> result = reviewMapper.selectPage(pageParam, new LambdaQueryWrapper<Review>()
                .eq(Review::getProductId, productId)
                .orderByDesc(Review::getId));
        return new PageResult<>(toVOList(result.getRecords()),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private List<ReviewVO> toVOList(List<Review> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return List.of();
        }
        Map<Long, Product> productMap = productMapper.selectBatchIds(
                        reviews.stream().map(Review::getProductId).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));
        // 匿名评价不查询昵称，避免泄露
        Map<Long, String> nicknameMap = userService.nicknames(reviews.stream()
                .filter(review -> review.getAnonymous() == null || review.getAnonymous() == 0)
                .map(Review::getUserId)
                .distinct()
                .toList());
        return reviews.stream()
                .map(review -> toVO(review, productMap.get(review.getProductId()), nicknameMap))
                .toList();
    }

    private ReviewVO toVO(Review review, Product product, Map<Long, String> nicknameMap) {
        ReviewVO vo = new ReviewVO();
        vo.setId(review.getId());
        vo.setProductId(review.getProductId());
        vo.setOrderNo(review.getOrderNo());
        if (product != null) {
            vo.setProductTitle(product.getTitle());
            vo.setProductCover(product.getCover());
        }
        vo.setScoreDesc(review.getScoreDesc());
        vo.setScoreLogistics(review.getScoreLogistics());
        vo.setScoreService(review.getScoreService());
        vo.setAvgScore(average(review));
        vo.setContent(review.getContent());
        vo.setImages(splitImages(review.getImages()));
        boolean anonymous = review.getAnonymous() != null && review.getAnonymous() == 1;
        vo.setAnonymous(anonymous);
        vo.setNickname(anonymous ? ANONYMOUS_NAME : nicknameMap.getOrDefault(review.getUserId(), DEFAULT_NAME));
        vo.setReply(review.getReply());
        vo.setCreateTime(review.getCreateTime());
        return vo;
    }

    private int average(Review review) {
        int desc = review.getScoreDesc() == null ? 0 : review.getScoreDesc();
        int logistics = review.getScoreLogistics() == null ? 0 : review.getScoreLogistics();
        int service = review.getScoreService() == null ? 0 : review.getScoreService();
        return Math.round((desc + logistics + service) / 3.0f);
    }

    private List<String> splitImages(String images) {
        if (!StringUtils.hasText(images)) {
            return List.of();
        }
        return Arrays.stream(images.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
