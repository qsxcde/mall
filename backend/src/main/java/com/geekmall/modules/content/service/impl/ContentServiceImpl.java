package com.geekmall.modules.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.modules.content.entity.Faq;
import com.geekmall.modules.content.entity.Policy;
import com.geekmall.modules.content.mapper.FaqMapper;
import com.geekmall.modules.content.mapper.PolicyMapper;
import com.geekmall.modules.content.service.ContentService;
import com.geekmall.modules.content.vo.AboutStatVO;
import com.geekmall.modules.content.vo.FaqVO;
import com.geekmall.modules.content.vo.PolicyVO;
import com.geekmall.modules.product.service.ProductService;
import com.geekmall.modules.trade.service.OrderService;
import com.geekmall.modules.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 内容服务实现。
 *
 * <p>统计数字通过各域的 service 获取，而不是直接读别的模块的表，
 * 这样内容域不需要知道商品/用户/订单的表结构。</p>
 */
@Service
@RequiredArgsConstructor
public class ContentServiceImpl implements ContentService {

    private final FaqMapper faqMapper;
    private final PolicyMapper policyMapper;
    private final ProductService productService;
    private final UserService userService;
    private final OrderService orderService;

    @Override
    public List<AboutStatVO> about() {
        return List.of(
                new AboutStatVO(String.valueOf(productService.countOnShelf()), "在售商品"),
                new AboutStatVO(String.valueOf(userService.countUsers()), "注册用户"),
                new AboutStatVO(String.valueOf(orderService.countAllOrders()), "累计订单"),
                new AboutStatVO("7×24", "小时客服"));
    }

    @Override
    public List<FaqVO> faqs() {
        return faqMapper.selectList(new LambdaQueryWrapper<Faq>()
                        .orderByAsc(Faq::getSort)
                        .orderByAsc(Faq::getId))
                .stream()
                .map(faq -> {
                    FaqVO vo = new FaqVO();
                    vo.setId(faq.getId());
                    vo.setQ(faq.getQuestion());
                    vo.setA(faq.getAnswer());
                    return vo;
                })
                .toList();
    }

    @Override
    public List<PolicyVO> policies() {
        return policyMapper.selectList(new LambdaQueryWrapper<Policy>()
                        .orderByAsc(Policy::getSort)
                        .orderByAsc(Policy::getId))
                .stream()
                .map(policy -> {
                    PolicyVO vo = new PolicyVO();
                    vo.setId(policy.getId());
                    vo.setTitle(policy.getTitle());
                    vo.setItems(splitItems(policy.getItems()));
                    return vo;
                })
                .toList();
    }

    /**
     * 条款切分：优先按换行（CMS 约定一行一条）；
     * 历史数据是单行用「；」分隔的，做一次兼容。
     */
    private List<String> splitItems(String items) {
        if (!StringUtils.hasText(items)) {
            return List.of();
        }
        List<String> byLine = Arrays.stream(items.split("\\r?\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (byLine.size() > 1) {
            return byLine;
        }
        return Arrays.stream(items.split("[；;]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
