package com.geekmall.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * 需要 Elasticsearch 的集成测试基类。
 *
 * <p><b>为什么单独一个基类而不是塞进 {@link AbstractIntegrationTest}</b>：
 * ES 容器要 9 秒启动、额外占 256MB 内存，而绝大多数测试（订单/支付/售后…）用不到它。
 * Testcontainers 容器是进程级单例，放进公共基类会让<b>每个测试类都为它付启动成本</b>。
 * 因此按需继承：只有搜索契约测试付这笔钱。</p>
 *
 * <h3>为什么测试里自己写 HTTP 灌数据，而不是复用应用代码</h3>
 * <p>应用内<b>没有</b> ES 同步服务（只有 {@code loadtest/scripts/es_sync_products.py}
 * 这个离线脚本）。契约测试需要一个可信的「写索引」通道，直接用内嵌 HTTP 客户端调
 * {@code _bulk} 最直接 —— 它同时也验证了索引 mapping 与 {@link com.geekmall.modules.product.search.ElasticProductSearchService}
 * 的字段名约定是否一致（字段名对不上，搜索就查不到，测试会立刻失败）。</p>
 */
public abstract class AbstractSearchIntegrationTest extends AbstractIntegrationTest {

    private static final String ES_IMAGE = "docker.elastic.co/elasticsearch/elasticsearch:8.15.3";

    protected static final ElasticsearchContainer ES;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static {
        ES = new ElasticsearchContainer(DockerImageName.parse(ES_IMAGE))
                // 测试只要搜索能力，不需要 TLS 与账号体系
                .withEnv("discovery.type", "single-node")
                .withEnv("xpack.security.enabled", "false")
                // 默认 1g 堆会让 CI runner 内存吃紧；测试数据量很小，256m 足够
                .withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m")
                .withStartupTimeout(Duration.ofMinutes(3));
        ES.start();

        Runtime.getRuntime().addShutdownHook(new Thread(ES::stop));
    }

    /**
     * 注入 ES 地址。
     *
     * <p>与父类的 {@code @DynamicPropertySource} 各自生效（Spring 会合并所有
     * {@code @DynamicPropertySource} 方法），这里只补充 ES 相关项。</p>
     */
    @org.springframework.test.context.DynamicPropertySource
    static void registerElasticsearchProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("mall.search.es.uris", ES::getHttpHostAddress);
        registry.add("mall.search.es.index", () -> INDEX);
        // 契约测试必须关闭降级：否则 ES 出错时会静默回落到 mysql-like，
        // 测试「通过」却根本没测到 ES —— 这是最危险的一类假绿。
        registry.add("mall.search.es.degrade-to-mysql", () -> false);
    }

    /** 测试索引名。 */
    protected static final String INDEX = "product";

    /**
     * 索引 mapping，与 {@code loadtest/scripts/es_sync_products.py} 保持一致。
     *
     * <p>{@code title} 用 standard 分词（英文按词、中文按字），足以支撑契约测试的
     * 子串/前缀场景；生产若要用中文分词器，应在这里与同步脚本同时改。</p>
     */
    private static final String INDEX_DEFINITION =
            """
            {
              "mappings": {
                "properties": {
                  "id":           {"type": "long"},
                  "shopId":       {"type": "long"},
                  "title":        {"type": "text", "analyzer": "standard"},
                  "brandName":    {"type": "keyword"},
                  "categoryKey":  {"type": "keyword"},
                  "parentKey":    {"type": "keyword"},
                  "categoryName": {"type": "keyword"},
                  "price":        {"type": "double"},
                  "sales":        {"type": "integer"},
                  "rating":       {"type": "double"},
                  "isHot":        {"type": "byte"},
                  "isNew":        {"type": "byte"},
                  "status":       {"type": "byte"}
                }
              }
            }
            """;

    /* ------------------------------ ES 操作辅助 ------------------------------ */

    /** 重建索引：删除后按固定 mapping 重新创建，保证用例之间不残留数据。 */
    protected void resetSearchIndex() {
        // 首次运行时索引并不存在，DELETE 返回 404 属正常（不是失败）
        esCallAllowingNotFound("DELETE", "/" + INDEX, null);
        esCall("PUT", "/" + INDEX, INDEX_DEFINITION);
    }

    /** 索引若干文档（走 _bulk，与同步脚本同一条路径）。 */
    protected void indexDocuments(List<Map<String, Object>> documents) {
        if (documents.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder();
        for (Map<String, Object> doc : documents) {
            body.append("{\"index\":{\"_index\":\"")
                    .append(INDEX)
                    .append("\",\"_id\":")
                    .append(doc.get("id"))
                    .append("}}\n");
            try {
                body.append(MAPPER.writeValueAsString(doc)).append('\n');
            } catch (Exception e) {
                throw new IllegalStateException("序列化 ES 文档失败", e);
            }
        }
        esCall("POST", "/_bulk", body.toString());
        // 契约测试会立刻查询，必须等索引可见；生产中靠 refresh_interval 自动刷新
        esCall("POST", "/" + INDEX + "/_refresh", null);
    }

    /** 执行一次 ES REST 调用；404 视为可接受（用于「删除可能不存在的索引」）。 */
    private void esCallAllowingNotFound(String method, String path, String body) {
        try {
            esCall(method, path, body);
        } catch (IllegalStateException e) {
            if (!e.getMessage().contains("HTTP 404")) {
                throw e;
            }
        }
    }

    /** 执行一次 ES REST 调用；非 2xx 直接失败（便于定位 mapping 或字段名写错）。 */
    private void esCall(String method, String path, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + ES.getHttpHostAddress() + path))
                    .timeout(Duration.ofSeconds(30));
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body));
            }
            HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "ES %s %s 失败：HTTP %d %s".formatted(method, path, response.statusCode(), response.body()));
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException) {
                throw (IllegalStateException) e;
            }
            throw new IllegalStateException("调用 ES 失败：" + method + " " + path, e);
        }
    }
}
