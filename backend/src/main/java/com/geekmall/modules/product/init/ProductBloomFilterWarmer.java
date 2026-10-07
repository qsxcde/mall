package com.geekmall.modules.product.init;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.cache.RedisBloomFilter;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商品布隆过滤器预热：启动时把在售商品 ID 灌入位图。
 *
 * <p>不预热的话，过滤器是空的，{@link RedisBloomFilter#isReady()} 会返回 false，
 * 所有商品详情请求都会走「跳过校验」分支 —— 功能正常但没有防护。
 * 预热完并打上就绪标记后，防穿透才真正生效。</p>
 *
 * <p><b>只在未就绪时预热</b>：多实例部署时若每个实例启动都全量重建，
 * 会出现「A 正在写位图、B 把它清空重写」的互相覆盖。就绪标记已存在即跳过，
 * 后续新增 / 上架商品由商家写接口增量维护（见 {@code MerchantProductServiceImpl}）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductBloomFilterWarmer implements ApplicationRunner {

    private static final int ON_SHELF = 1;

    private final ProductMapper productMapper;
    private final RedisBloomFilter productBloomFilter;

    @Override
    public void run(ApplicationArguments args) {
        if (productBloomFilter.isReady()) {
            log.info("商品布隆过滤器已就绪，跳过预热（新增商品由上架接口增量维护）");
            return;
        }
        List<String> ids = productMapper.selectList(new LambdaQueryWrapper<Product>()
                        .select(Product::getId)
                        .eq(Product::getStatus, ON_SHELF))
                .stream()
                .map(product -> String.valueOf(product.getId()))
                .toList();
        // 不设 TTL：位图应长期存在；Redis 被清空时就绪标记一并消失，
        // 下一次启动会重新预热，期间自动降级为「跳过校验」而不是全站 404。
        productBloomFilter.rebuild(ids, null);
        log.info("商品布隆过滤器预热完成，共 {} 个在售商品", ids.size());
    }
}
