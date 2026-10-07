package com.geekmall.modules.merchant.converter;

import com.geekmall.modules.merchant.vo.BuyerVO;
import com.geekmall.modules.merchant.vo.MerchantReviewVO;
import com.geekmall.modules.merchant.vo.OrderProductVO;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.review.entity.Review;
import com.geekmall.modules.user.entity.SysUser;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 商家端评价对象转换。
 *
 * <p>综合评分取三个维度（描述/物流/服务）的均值并四舍五入为整数星级，
 * 与前端 1~5 星展示口径一致。</p>
 */
public final class MerchantReviewConverter {

    private MerchantReviewConverter() {
    }

    public static MerchantReviewVO toVO(Review review, Product product, SysUser buyer) {
        if (review == null) {
            return null;
        }
        MerchantReviewVO vo = new MerchantReviewVO();
        vo.setId("RV" + review.getId());
        vo.setOrderId(review.getOrderNo());
        int rating = averageRating(review);
        vo.setRating(rating);
        vo.setIsBad(rating <= 2);
        vo.setIsGood(rating >= 4);
        vo.setContent(review.getContent());
        vo.setImages(countImages(review.getImages()));
        vo.setTags(List.of());
        vo.setStatus(resolveStatus(review));
        vo.setReply(review.getReply());
        vo.setRepliedAt(StringUtils.hasText(review.getReply()) ? review.getUpdateTime() : null);
        vo.setHelpful(0);
        vo.setCreatedAt(review.getCreateTime());
        if (product != null) {
            OrderProductVO p = new OrderProductVO();
            p.setId(product.getId());
            p.setName(product.getTitle());
            p.setCover(product.getCover());
            p.setSpec(product.getSpec());
            p.setPrice(product.getPrice());
            vo.setProduct(p);
            vo.setSku(product.getSpec());
        }
        if (buyer != null) {
            BuyerVO b = new BuyerVO();
            b.setName(buyer.getNickname() == null ? buyer.getUsername() : buyer.getNickname());
            b.setLevel(buyer.getLevelId() == null ? null : "V" + buyer.getLevelId());
            vo.setBuyer(b);
        }
        return vo;
    }

    /** 三维度均值四舍五入为整数星级。 */
    public static int averageRating(Review review) {
        int desc = review.getScoreDesc() == null ? 5 : review.getScoreDesc();
        int logistics = review.getScoreLogistics() == null ? 5 : review.getScoreLogistics();
        int service = review.getScoreService() == null ? 5 : review.getScoreService();
        return BigDecimal.valueOf((desc + logistics + service) / 3.0)
                .setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /** 三维度均值（保留 2 位小数），供 DSR 汇总使用。 */
    public static double averageScore(Review review) {
        int desc = review.getScoreDesc() == null ? 5 : review.getScoreDesc();
        int logistics = review.getScoreLogistics() == null ? 5 : review.getScoreLogistics();
        int service = review.getScoreService() == null ? 5 : review.getScoreService();
        return (desc + logistics + service) / 3.0;
    }

    private static String resolveStatus(Review review) {
        if (review.getIgnored() != null && review.getIgnored() == 1) {
            return "ignored";
        }
        return StringUtils.hasText(review.getReply()) ? "replied" : "wait";
    }

    private static Integer countImages(String images) {
        if (!StringUtils.hasText(images)) {
            return 0;
        }
        return (int) java.util.Arrays.stream(images.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .count();
    }
}
