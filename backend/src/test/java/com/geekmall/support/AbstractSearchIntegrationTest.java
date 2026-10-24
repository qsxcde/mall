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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 需要 Elasticsearch 的集成测试基类。
 *
 * <p><b>为什么单独一个基类而不是塞进 {@link AbstractIntegrationTest}</b>：
 * ES 容器要 9 秒启动、额外占 256MB 内存，而绝大多数测试（订单/支付/售后…）用不到它。
 * Testcontainers 容器是进程级单例，放进公共基类会让<b>每个测试类都为它付启动成本</b>。
 * 因此按需继承：只有搜索契约测试付这笔钱。</p>
 *
 * <h3>为什么契约测试自己写 HTTP 灌数据，而不是复用应用代码</h3>
 * <p>契约测试要对比<b>三种引擎</b>（LIKE / FULLTEXT / ES）在同一批数据上的行为，
 * 因此数据必须独立于应用选用的引擎写入 —— 直接用内嵌 HTTP 客户端调 {@code _bulk}
 * 最直接，同时也验证了索引 mapping 与 {@link com.geekmall.modules.product.search.ElasticProductSearchService}
 * 的字段名约定是否一致（字段名对不上，搜索就查不到，测试会立刻失败）。</p>
 *
 * <p>应用侧的增量索引链路（{@code ProductChangedEvent} → {@code ProductIndexSyncListener}
 * → {@code ElasticProductSearchIndexer}）由 {@code ProductIndexSyncIntegrationTest} 验证：
 * 那个测试会让 Spring 以 {@code mall.search.engine=elasticsearch} 启动，
 * 从而真正走到生产代码路径，而不是测试自己灌数据。</p>
 */
public abstract class AbstractSearchIntegrationTest extends AbstractIntegrationTest {

    /**
     * ES 镜像版本必须与客户端大版本一致：客户端 9.x 只保证与 9.x 及更高服务端兼容
     * （兼容模式只覆盖「旧客户端 → 新服务端」方向）。
     */
    private static final String ES_IMAGE = "docker.elastic.co/elasticsearch/elasticsearch:9.4.5";

    protected static final ElasticsearchContainer ES;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static {
        ES = new ElasticsearchContainer(DockerImageName.parse(ES_IMAGE))
                // 测试只要搜索能力，不需要 TLS 与账号体系
                .withEnv("discovery.type", "single-node")
                .withEnv("xpack.security.enabled", "false")
                // ES 9 启动比 8.x 更吃内存：256m 在本机要 ~170s 才可用，接近超时上限。
                // 512m 在 CI runner（7GB）上无压力，同时把启动超时放宽到 5 分钟避免抖动。
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withStartupTimeout(Duration.ofMinutes(5));
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
        // 必须带 http:// 前缀：getHttpHostAddress() 只返回 host:port，而生产配置
        // （application.yml 的 ES_URIS 默认值）是完整 URL。带上前缀才能让测试与生产
        // 使用同一份解析逻辑，而不是绕过解析直接连。
        registry.add("mall.search.es.uris", () -> "http://" + ES.getHttpHostAddress());
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

    /**
     * 强制刷新索引，让刚写入的文档立即可见。
     *
     * <p>生产索引的 {@code refresh_interval} 是 5s（拿写入吞吐换可见延迟），
     * 断言前必须显式 refresh —— 否则测试会随机失败，并被误判成「同步没生效」。</p>
     */
    protected void refreshSearchIndex() {
        // 索引可能还没被应用创建出来（自动建索引的用例），此时 refresh 返回 404 属正常
        esCallAllowingNotFound("POST", "/" + INDEX + "/_refresh", null);
    }

    /**
     * 读取索引中的单条文档；不存在返回 {@code null}。
     *
     * <p>直接读 {@code _source} 而不是「搜一下看有没有命中」：
     * 前者能断言<b>具体字段值</b>（比如 cost 不在文档里、price 已更新），
     * 后者只能证明「能搜到」，字段写错照样命中不到——那是假绿。</p>
     */
    protected Map<String, Object> getIndexedDocument(long id) {
        try {
            String body = httpCall("GET", "/" + INDEX + "/_doc/" + id, null);
            JsonNode root = MAPPER.readTree(body);
            if (!root.has("found") || !root.get("found").asBoolean()) {
                return null;
            }
            return MAPPER.convertValue(root.get("_source"), new TypeReference<Map<String, Object>>() {});
        } catch (IllegalStateException e) {
            if (e.getMessage().contains("HTTP 404")) {
                return null;
            }
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取 ES 文档失败：" + id, e);
        }
    }

    /** 索引中的文档总数。 */
    protected long searchIndexCount() {
        try {
            JsonNode root = MAPPER.readTree(httpCall("GET", "/" + INDEX + "/_count", null));
            return root.get("count").asLong();
        } catch (Exception e) {
            throw new IllegalStateException("读取 ES 文档数失败", e);
        }
    }

    /** 删除索引（用于模拟「新环境还没有索引」）。 */
    protected void deleteSearchIndex() {
        esCallAllowingNotFound("DELETE", "/" + INDEX, null);
    }

    /** 索引是否存在。 */
    protected boolean indexExists() {
        try {
            httpCall("HEAD", "/" + INDEX, null);
            return true;
        } catch (IllegalStateException e) {
            if (e.getMessage().contains("HTTP 404")) {
                return false;
            }
            throw e;
        }
    }

    /**
     * 读取索引中某字段的 ES 类型。
     *
     * <p>用来区分「显式 mapping」与「动态推断」：两者都能存数据、都能搜，
     * 但类型不同（{@code scaled_float} vs {@code float}、{@code keyword} vs {@code text}）。
     * 只断言「搜到了几条」是看不出这个差异的。</p>
     */
    protected String fieldType(String field) {
        try {
            JsonNode root = MAPPER.readTree(httpCall("GET", "/" + INDEX + "/_mapping", null));
            JsonNode properties = root.path(INDEX).path("mappings").path("properties");
            JsonNode fieldNode = properties.get(field);
            return fieldNode == null ? null : fieldNode.get("type").asText();
        } catch (Exception e) {
            throw new IllegalStateException("读取索引 mapping 失败：" + field, e);
        }
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
        httpCall(method, path, body);
    }

    /** 执行一次 ES REST 调用并返回响应体。 */
    private String httpCall(String method, String path, String body) {
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
            return response.body();
        } catch (Exception e) {
            if (e instanceof IllegalStateException) {
                throw (IllegalStateException) e;
            }
            throw new IllegalStateException("调用 ES 失败：" + method + " " + path, e);
        }
    }
}
