package com.geekmall.modules.product.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.geekmall.common.result.PageResult;
import com.geekmall.modules.product.converter.ProductConverter;
import com.geekmall.modules.product.dto.ProductQueryDTO;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.product.vo.ProductCardVO;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Elasticsearch 搜索实现。
 *
 * <p>通过 {@code mall.search.engine=elasticsearch} 启用。</p>
 *
 * <h3>核心设计：ES 只负责「找 id」，内容回 MySQL 取</h3>
 * <p>ES 索引有同步延迟（{@code refresh_interval}），价格、库存、上下架状态这些
 * <b>强一致字段</b>绝不能直接展示索引副本 —— 否则会出现「搜到了但点进去已下架」。
 * 因此流程是：ES 返回有序 id 列表 → 按 id 批量回表 → 用现有
 * {@link ProductConverter} 组装 VO。前端契约零变化。</p>
 *
 * <h3>为什么用 low-level 客户端而不是 Spring Data Elasticsearch</h3>
 * <p>Spring Data ES 对 Boot 版本与 ES 版本有严格的三方匹配约束（Boot 3.3 只能配 SDE 5.3.x /
 * ES 8.13），且 Repository 抽象会挡住 DSL 细节。这里直接构造 DSL，
 * 排序、过滤、相关度打分都能显式控制，便于与 MySQL 方案逐条对齐语义。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "mall.search.engine", havingValue = "elasticsearch")
public class ElasticProductSearchService implements ProductSearchService {

    private final ProductMapper productMapper;
    private final ElasticsearchClient client;
    private final String index;
    private final boolean degradeToMysql;
    private final LikeProductSearchService mysqlFallback;

    public ElasticProductSearchService(
            ProductMapper productMapper,
            @Value("${mall.search.es.uris:http://localhost:9200}") String uris,
            @Value("${mall.search.es.index:product}") String index,
            @Value("${mall.search.es.degrade-to-mysql:true}") boolean degradeToMysql,
            @Value("${mall.search.es.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${mall.search.es.socket-timeout-ms:5000}") int socketTimeoutMs) {
        this.productMapper = productMapper;
        // 降级实现不注册成 Bean（条件装配互斥），直接构造一个只依赖 Mapper 的实例
        this.mysqlFallback = LikeProductSearchService.forFallback(productMapper);
        this.index = index;
        this.degradeToMysql = degradeToMysql;
        this.client = buildClient(uris, connectTimeoutMs, socketTimeoutMs);
    }

    /**
     * 构建 ES 客户端。
     *
     * <p><b>超时必须显式设置</b>：官方 low-level 客户端的默认 socket 超时是 30s，
     * 与整个搜索接口的目标延迟（毫秒级）严重不匹配 —— ES 一旦变慢而不是直接拒绝，
     * 请求线程会被挂住几十秒，进而耗尽 Tomcat 线程池。这里的 2s/5s 是
     * 「宁可判定失败并降级到 MySQL，也不要拖住调用方」。</p>
     */
    private static ElasticsearchClient buildClient(String uris, int connectTimeoutMs, int socketTimeoutMs) {
        List<HttpHost> hosts = new ArrayList<>();
        for (String uri : uris.split(",")) {
            String trimmed = uri.trim();
            if (!trimmed.isEmpty()) {
                hosts.add(HttpHost.create(trimmed));
            }
        }
        RestClient restClient = RestClient.builder(hosts.toArray(new HttpHost[0]))
                .setRequestConfigCallback(
                        config -> config.setConnectTimeout(connectTimeoutMs).setSocketTimeout(socketTimeoutMs))
                .build();
        return new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
    }

    @Override
    public PageResult<ProductCardVO> search(ProductQueryDTO query) {
        long page = query.getPage() == null ? 1 : Math.max(1, query.getPage());
        long pageSize = query.getPageSize() == null ? 8 : Math.max(1, query.getPageSize());

        try {
            return doSearch(query, page, pageSize);
        } catch (Exception ex) {
            // ES 不可用时回落 MySQL：搜索变慢，但不中断。
            if (degradeToMysql) {
                log.warn("[搜索降级] ES 查询失败，回落到 mysql-like：{}", ex.getMessage());
                return mysqlFallback.search(query);
            }
            throw new IllegalStateException("搜索服务不可用", ex);
        }
    }

    private PageResult<ProductCardVO> doSearch(ProductQueryDTO query, long page, long pageSize) throws Exception {
        BoolQuery.Builder bool = new BoolQuery.Builder();

        // 与 mysql 实现保持一致：只查上架商品
        bool.filter(f -> f.term(t -> t.field("status").value(1)));

        if (StringUtils.hasText(query.getSub())) {
            bool.filter(f -> f.term(t -> t.field("categoryKey").value(query.getSub())));
        } else if (StringUtils.hasText(query.getCat())) {
            bool.filter(f -> f.term(t -> t.field("parentKey").value(query.getCat())));
        }

        if (StringUtils.hasText(query.getBrand())) {
            List<String> brands = java.util.Arrays.stream(query.getBrand().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (!brands.isEmpty()) {
                bool.filter(f -> f.terms(t -> t.field("brandName")
                        .terms(v -> v.value(brands.stream()
                                .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                                .toList()))));
            }
        }

        if (query.getPriceMin() != null) {
            bool.filter(f -> f.range(
                    r -> r.number(n -> n.field("price").gte(query.getPriceMin().doubleValue()))));
        }
        if (query.getPriceMax() != null) {
            bool.filter(f -> f.range(
                    r -> r.number(n -> n.field("price").lte(query.getPriceMax().doubleValue()))));
        }
        if (Boolean.TRUE.equals(query.getHot())) {
            bool.filter(f -> f.term(t -> t.field("isHot").value(1)));
        }
        if (Boolean.TRUE.equals(query.getIsNew())) {
            bool.filter(f -> f.term(t -> t.field("isNew").value(1)));
        }

        // 关键词：多字段匹配，标题权重最高（这是 LIKE 给不了的能力）
        // operator=and 保证多个词必须同时出现，避免召回过多无关商品
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            bool.must(m -> m.multiMatch(mm -> mm.fields("title^3", "brandName^2", "categoryName")
                    .query(keyword)
                    .operator(co.elastic.clients.elasticsearch._types.query_dsl.Operator.And)));
        }

        // 排序：与 mysql 的 applySort 一一对应。
        //
        // 相关度（_score）只在「用户没有显式指定排序」时才作为主排序键。
        // 一旦用户点了「价格从低到高」，就必须严格按价格排 —— 若把 _score 放首位，
        // 价格排序会退化成一个几乎无效的次级条件（实测会返回 7499 / 12999 / 1499，
        // 看起来很随机）。这是 ES 与 LIKE 行为差异最容易踩的坑：
        // LIKE 方案里关键词只影响 WHERE，不参与 ORDER BY。
        final boolean hasKeyword = StringUtils.hasText(query.getKeyword());
        final String sort = query.getSort() == null ? "" : query.getSort();

        SearchResponse<Void> resp = client.search(
                s -> {
                    s.index(index)
                            .query(q -> q.bool(bool.build()))
                            .from((int) ((page - 1) * pageSize))
                            .size((int) pageSize)
                            .trackTotalHits(t -> t.enabled(true));
                    // 只取 id，字段内容一律回表，避免索引副本与数据库不一致
                    s.source(src -> src.filter(f -> f.includes("id")));

                    switch (sort) {
                        case "sales" -> s.sort(
                                so -> so.field(f -> f.field("sales").order(SortOrder.Desc)));
                        case "price_asc" -> s.sort(
                                so -> so.field(f -> f.field("price").order(SortOrder.Asc)));
                        case "price_desc" -> s.sort(
                                so -> so.field(f -> f.field("price").order(SortOrder.Desc)));
                        case "rating" -> s.sort(
                                so -> so.field(f -> f.field("rating").order(SortOrder.Desc)));
                        case "new" -> s.sort(so -> so.field(f -> f.field("id").order(SortOrder.Desc)));
                        default -> {
                            if (hasKeyword) {
                                // 未指定排序：相关度优先，同分时销量高的靠前
                                s.sort(so -> so.score(sc -> sc.order(SortOrder.Desc)));
                                s.sort(so -> so.field(f -> f.field("sales").order(SortOrder.Desc)));
                            } else {
                                s.sort(so -> so.field(f -> f.field("isHot").order(SortOrder.Desc)));
                                s.sort(so -> so.field(f -> f.field("sales").order(SortOrder.Desc)));
                            }
                        }
                    }
                    return s;
                },
                Void.class);

        long total = resp.hits().total() == null ? 0 : resp.hits().total().value();
        if (total == 0) {
            return PageResult.empty(page, pageSize);
        }

        List<Long> ids = new ArrayList<>();
        for (Hit<Void> hit : resp.hits().hits()) {
            if (hit.id() != null) {
                ids.add(Long.valueOf(hit.id()));
            }
        }

        List<Product> ordered = LikeProductSearchService.loadInOrder(productMapper, ids);
        return new PageResult<>(ordered.stream().map(ProductConverter::toCard).toList(), total, page, pageSize);
    }

    @Override
    public String engineName() {
        return "elasticsearch";
    }
}
