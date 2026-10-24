package com.geekmall.modules.product.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.service.ProductStockService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Elasticsearch 增量索引写入实现（{@code mall.search.engine=elasticsearch} 时启用）。
 *
 * <h3>为什么「按 id 重读数据库」而不是「用事件里的快照」</h3>
 * <p>索引是派生数据，唯一可信来源是 MySQL。重读一次的开销是<b>一次主键查询</b>，
 * 换来的是幂等：重复投递、乱序投递、批量改价里同一商品被连改两遍，最终都会收敛到
 * 「索引 = 数据库当前状态」。反之若把事件快照写进去，一条迟到的旧事件就能把索引
 * 回退到旧价格 —— 这类 bug 在压测里几乎不可能复现。</p>
 *
 * <h3>失败策略：只降级搜索新鲜度，不影响写请求</h3>
 * <p>本类的所有外部调用都被 {@code try/catch} 包住并降级为告警日志。理由很直接：
 * ES 挂了不应该让商家保存不了商品。索引落后的代价是「搜不到最新改价」，
 * 而写请求失败的代价是「业务不可用」，两者不在一个量级。</p>
 *
 * <h3>已知边界：销量不逐单同步</h3>
 * <p>下单扣减库存会同时 {@code sales + qty}（见 {@code ProductMapper.deductStock}）。
 * 这条路径在每次下单时都会走，若也发一次索引写入，等于给下单链路加一个外部依赖 ——
 * 不值得。因此索引里的 {@code sales} 只随商品维护写路径更新，销量排序会滞后；
 * 命中集与展示内容不受影响（ES 只返回 id，内容回表取），
 * 需要精确销量排序时重跑一次全量脚本即可。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "mall.search.engine", havingValue = "elasticsearch")
public class ElasticProductSearchIndexer implements ProductSearchIndexer {

    private final ProductStockService productStockService;
    private final ElasticsearchClient client;
    private final String index;

    public ElasticProductSearchIndexer(
            ProductStockService productStockService,
            @Value("${mall.search.es.uris:http://localhost:9200}") String uris,
            @Value("${mall.search.es.index:product}") String index,
            @Value("${mall.search.es.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${mall.search.es.socket-timeout-ms:5000}") int socketTimeoutMs) {
        this.productStockService = productStockService;
        this.index = index;
        this.client = EsClientFactory.build(uris, connectTimeoutMs, socketTimeoutMs);
    }

    @Override
    public void sync(Long productId) {
        if (productId == null) {
            return;
        }
        try {
            // 逻辑删除的商品 selectById 直接返回 null，天然覆盖「删商品」这条路径
            Product product = productStockService.findById(productId);
            if (!ProductIndexDocument.indexable(product)) {
                removeFromIndex(productId);
                return;
            }
            ensureIndex();
            writeDocument(product);
        } catch (Exception e) {
            // 索引失败不能让商家的保存/上下架失败：降级为告警，等下次写入或全量脚本纠正
            log.warn("[搜索索引] 商品 {} 同步失败，索引暂时滞后：{}", productId, e.getMessage());
        }
    }

    @Override
    public String engineName() {
        return "elasticsearch";
    }

    /**
     * 写入单条文档。
     *
     * <p>遇到 {@code index_not_found_exception} 时重建索引并重试一次：
     * 索引可能在探测之后、写入之前被运维删掉（探测与写入不是原子的）。
     * 自愈比让一次竞态变成永久性写失败更重要。</p>
     */
    private void writeDocument(Product product) throws Exception {
        IndexRequest<Map<String, Object>> request = IndexRequest.of(
                b -> b.index(index).id(String.valueOf(product.getId())).document(ProductIndexDocument.of(product)));
        try {
            client.index(request);
        } catch (ElasticsearchException e) {
            if (!"index_not_found_exception".equals(e.error().type())) {
                throw e;
            }
            ensureIndex();
            client.index(request);
        }
    }

    /** 从索引中删除；文档本就不存在（404）属正常情况，不算失败。 */
    private void removeFromIndex(Long productId) throws Exception {
        try {
            client.delete(d -> d.index(index).id(String.valueOf(productId)));
        } catch (ElasticsearchException e) {
            // 索引/文档都不存在时无需删除：这正是「下架商品不在索引里」的目标状态
            if (e.status() != 404
                    && !"index_not_found_exception".equals(e.error().type())) {
                throw e;
            }
        }
    }

    /**
     * 确保索引存在且 mapping 正确。
     *
     * <p><b>每次都探测，不缓存结果</b>。曾经的写法是用一个 {@code AtomicBoolean}
     * 记住「已经确认过」，省掉每条的一次 HEAD 往返 —— 但那会引入一个很隐蔽的故障：
     * 索引被运维删掉（或重建）后，缓存仍是 true，于是 {@code client.index()} 直接写，
     * ES 会用<b>动态推断</b>悄悄建出一个新索引（{@code price} 变 float、
     * {@code title} 变 text+keyword），不报错、只是从此搜不对。商品维护是低频写路径，
     * 一次 HEAD 的代价远小于「索引类型悄悄错掉」。</p>
     *
     * <p>交给我们创建而不是只依赖离线脚本，是因为「新环境先跑脚本再启应用」这个顺序
     * 没人会记得；忘了就会出现上面说的动态推断问题。</p>
     */
    private void ensureIndex() throws Exception {
        if (Boolean.TRUE.equals(client.indices().exists(e -> e.index(index)).value())) {
            return;
        }
        try {
            client.indices().create(createIndexRequest());
            log.info("[搜索索引] 已自动创建索引 {}（mapping 与全量同步脚本一致）", index);
        } catch (ElasticsearchException e) {
            // 并发下另一个线程/实例抢先建好了：只要不是真失败就继续
            if (!"resource_already_exists_exception".equals(e.error().type())) {
                throw e;
            }
        }
    }

    /** 索引定义：字段与类型必须与 {@code loadtest/scripts/es_sync_products.py} 保持一致。 */
    private CreateIndexRequest createIndexRequest() {
        Map<String, Property> properties = new LinkedHashMap<>();
        properties.put("id", Property.of(p -> p.long_(l -> l)));
        properties.put("shopId", Property.of(p -> p.long_(l -> l)));
        properties.put("title", Property.of(p -> p.text(t -> t.analyzer("standard"))));
        properties.put("brandName", Property.of(p -> p.keyword(k -> k)));
        properties.put("categoryKey", Property.of(p -> p.keyword(k -> k)));
        properties.put("parentKey", Property.of(p -> p.keyword(k -> k)));
        properties.put("categoryName", Property.of(p -> p.keyword(k -> k)));
        properties.put("price", Property.of(p -> p.scaledFloat(s -> s.scalingFactor(100.0))));
        properties.put("sales", Property.of(p -> p.integer(i -> i)));
        properties.put("rating", Property.of(p -> p.halfFloat(h -> h)));
        properties.put("isHot", Property.of(p -> p.byte_(b -> b)));
        properties.put("isNew", Property.of(p -> p.byte_(b -> b)));
        properties.put("status", Property.of(p -> p.byte_(b -> b)));
        properties.put(
                "releaseTime",
                Property.of(
                        p -> p.date(d -> d.format("yyyy-MM-dd HH:mm:ss||strict_date_optional_time||epoch_millis"))));

        return CreateIndexRequest.of(c -> c.index(index)
                .settings(s -> s.numberOfShards("1")
                        .numberOfReplicas("0")
                        // 与全量脚本一致：5s 刷新一次。搜索侧本就靠回表保证强一致，
                        // 这里追求的是写入吞吐，而不是「写完立刻可见」
                        .refreshInterval(Time.of(t -> t.time("5s"))))
                .mappings(TypeMapping.of(m -> m.properties(properties))));
    }
}
