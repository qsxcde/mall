package com.geekmall.modules.merchant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.merchant.converter.MerchantReviewConverter;
import com.geekmall.modules.merchant.dto.MerchantReviewQueryDTO;
import com.geekmall.modules.merchant.dto.ReviewReplyDTO;
import com.geekmall.modules.merchant.service.MerchantReviewService;
import com.geekmall.modules.merchant.support.Numbers;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import com.geekmall.modules.merchant.vo.MerchantReviewVO;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.review.entity.Review;
import com.geekmall.modules.review.mapper.ReviewMapper;
import com.geekmall.modules.user.entity.SysUser;
import com.geekmall.modules.user.mapper.SysUserMapper;
import com.geekmall.security.MerchantSecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商家端评价管理服务实现。
 */
@Service
@RequiredArgsConstructor
public class MerchantReviewServiceImpl implements MerchantReviewService {

    private static final String RATING_EXPR = "(score_desc + score_logistics + score_service) / 3.0";

    private final ReviewMapper reviewMapper;
    private final ProductMapper productMapper;
    private final SysUserMapper sysUserMapper;

    @Override
    public MerchantPageVO<MerchantReviewVO> page(MerchantReviewQueryDTO query) {
        Long shopId = MerchantSecurityUtils.getShopId();
        LambdaQueryWrapper<Review> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Review::getShopId, shopId);
        applyTabFilter(wrapper, query.getTab());
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword().trim();
            wrapper.and(w -> w.like(Review::getContent, kw)
                    .or().like(Review::getOrderNo, kw)
                    .or().apply("user_id IN (SELECT id FROM sys_user WHERE nickname LIKE CONCAT('%', {0}, '%'))", kw)
                    .or().apply("product_id IN (SELECT id FROM pms_product WHERE title LIKE CONCAT('%', {0}, '%'))", kw));
        }
        applySort(wrapper, query.getSort());

        Page<Review> page = new Page<>(query.getPage(), query.getSize());
        IPage<Review> result = reviewMapper.selectPage(page, wrapper);
        List<MerchantReviewVO> list = assemble(result.getRecords());

        MerchantPageVO<MerchantReviewVO> vo = new MerchantPageVO<>(
                list, result.getTotal(), result.getCurrent(), result.getSize());
        List<Review> all = reviewMapper.selectList(new LambdaQueryWrapper<Review>().eq(Review::getShopId, shopId));
        vo.put("tabs", buildTabs(all));
        vo.put("stats", buildStats(all));
        return vo;
    }

    @Override
    public Map<String, Object> summary() {
        Long shopId = MerchantSecurityUtils.getShopId();
        List<Review> all = reviewMapper.selectList(new LambdaQueryWrapper<Review>().eq(Review::getShopId, shopId));
        double desc = average(all, Review::getScoreDesc);
        double logistics = average(all, Review::getScoreLogistics);
        double service = average(all, Review::getScoreService);

        List<Map<String, Object>> dsr = new ArrayList<>();
        dsr.add(dsrItem("desc", "描述相符", desc, 4.78));
        dsr.add(dsrItem("service", "服务态度", service, 4.81));
        dsr.add(dsrItem("logistics", "物流服务", logistics, 4.84));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dsr", dsr);
        // 评价标签依赖 NLP 打标能力，当前后端无数据源，返回空集合
        result.put("tags", List.of());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reply(ReviewReplyDTO dto) {
        Long shopId = MerchantSecurityUtils.getShopId();
        Review review = reviewMapper.selectOne(new LambdaQueryWrapper<Review>()
                .eq(Review::getShopId, shopId)
                .eq(Review::getId, parseId(dto.getId()))
                .last("limit 1"));
        if (review == null) {
            throw BizException.of(ResultCode.NOT_FOUND);
        }
        Review update = new Review();
        update.setId(review.getId());
        update.setReply(dto.getContent());
        reviewMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int ignore(List<String> ids) {
        Long shopId = MerchantSecurityUtils.getShopId();
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<Long> pks = ids.stream().map(this::parseId).toList();
        int affected = 0;
        List<Review> reviews = reviewMapper.selectList(new LambdaQueryWrapper<Review>()
                .eq(Review::getShopId, shopId)
                .in(Review::getId, pks));
        for (Review review : reviews) {
            Review update = new Review();
            update.setId(review.getId());
            update.setIgnored(1);
            affected += reviewMapper.updateById(update);
        }
        return affected;
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private void applyTabFilter(LambdaQueryWrapper<Review> wrapper, String tab) {
        if (!StringUtils.hasText(tab) || "all".equals(tab)) {
            return;
        }
        switch (tab) {
            case "wait" -> wrapper.eq(Review::getIgnored, 0)
                    .and(w -> w.isNull(Review::getReply).or().eq(Review::getReply, ""));
            case "replied" -> wrapper.isNotNull(Review::getReply).ne(Review::getReply, "");
            case "bad" -> wrapper.apply(RATING_EXPR + " <= 2");
            case "good" -> wrapper.apply(RATING_EXPR + " >= 4");
            case "pic" -> wrapper.isNotNull(Review::getImages).ne(Review::getImages, "");
            default -> {
                // 未知 Tab 等同于「全部」
            }
        }
    }

    private void applySort(LambdaQueryWrapper<Review> wrapper, String sort) {
        String key = sort == null ? "" : sort;
        switch (key) {
            case "time_asc" -> wrapper.orderByAsc(Review::getCreateTime);
            case "rating_asc" -> wrapper.last("ORDER BY " + RATING_EXPR + " ASC");
            case "rating_desc" -> wrapper.last("ORDER BY " + RATING_EXPR + " DESC");
            default -> wrapper.orderByDesc(Review::getCreateTime);
        }
    }

    private List<MerchantReviewVO> assemble(List<Review> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return List.of();
        }
        Set<Long> productIds = reviews.stream().map(Review::getProductId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Product> productMap = productIds.isEmpty() ? Map.of()
                : productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));
        Set<Long> userIds = reviews.stream().map(Review::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SysUser> userMap = userIds.isEmpty() ? Map.of()
                : sysUserMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));

        return reviews.stream().map(r -> MerchantReviewConverter.toVO(
                r, productMap.get(r.getProductId()), userMap.get(r.getUserId()))).toList();
    }

    private Map<String, Object> buildTabs(List<Review> all) {
        Map<String, Object> tabs = new LinkedHashMap<>();
        tabs.put("all", (long) all.size());
        tabs.put("wait", all.stream().filter(this::isWait).count());
        tabs.put("replied", all.stream().filter(r -> StringUtils.hasText(r.getReply())).count());
        tabs.put("bad", all.stream().filter(r -> MerchantReviewConverter.averageRating(r) <= 2).count());
        tabs.put("good", all.stream().filter(r -> MerchantReviewConverter.averageRating(r) >= 4).count());
        tabs.put("pic", all.stream().filter(r -> StringUtils.hasText(r.getImages())).count());
        return tabs;
    }

    private Map<String, Object> buildStats(List<Review> all) {
        int total = all.size();
        long wait = all.stream().filter(this::isWait).count();
        long good = all.stream().filter(r -> MerchantReviewConverter.averageRating(r) >= 4).count();
        long mid = all.stream().filter(r -> MerchantReviewConverter.averageRating(r) == 3).count();
        long bad = all.stream().filter(r -> MerchantReviewConverter.averageRating(r) <= 2).count();
        double avg = all.stream().mapToDouble(MerchantReviewConverter::averageScore).average().orElse(0);

        List<Map<String, Object>> distribution = new ArrayList<>();
        for (int star = 5; star >= 1; star -= 1) {
            int s = star;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("star", s);
            item.put("count", all.stream().filter(r -> MerchantReviewConverter.averageRating(r) == s).count());
            distribution.add(item);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("wait", wait);
        stats.put("avg", Numbers.round(avg, 2));
        stats.put("goodRate", Numbers.round(Numbers.divide(good, total), 4));
        stats.put("badCount", bad);
        stats.put("midCount", mid);
        stats.put("withImage", all.stream().filter(r -> StringUtils.hasText(r.getImages())).count());
        stats.put("distribution", distribution);
        return stats;
    }

    private boolean isWait(Review review) {
        boolean notReplied = !StringUtils.hasText(review.getReply());
        boolean notIgnored = review.getIgnored() == null || review.getIgnored() == 0;
        return notReplied && notIgnored;
    }

    private double average(List<Review> reviews, Function<Review, Integer> getter) {
        return reviews.stream()
                .map(getter)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average().orElse(0);
    }

    private Map<String, Object> dsrItem(String key, String label, double score, double industry) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", label);
        item.put("score", Numbers.round(score, 2));
        item.put("industry", industry);
        item.put("delta", Numbers.round(score - industry, 3));
        return item;
    }

    private Long parseId(String id) {
        if (!StringUtils.hasText(id) || !id.startsWith("RV")) {
            throw new BizException(ResultCode.PARAM_ERROR, "非法的评价 ID：" + id);
        }
        try {
            return Long.parseLong(id.substring(2));
        } catch (NumberFormatException e) {
            throw new BizException(ResultCode.PARAM_ERROR, "非法的评价 ID：" + id);
        }
    }
}
