package com.geekmall.modules.product.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;

/**
 * ES 客户端构建（搜索与索引写入共用）。
 *
 * <p><b>为什么单独抽出来</b>：超时配置是这里最容易写错、也最致命的一段
 * （默认 socket 超时 30s 会挂死 Tomcat 线程）。搜索和增量索引都需要同一份
 * 超时语义，各写一遍迟早漂移；抽成一处后，两边的连接参数永远一致。</p>
 *
 * <p>本类不依赖 Spring 容器：调用方把配置值传进来即可，
 * 这样契约测试能直接 {@code new} 出实现来对比三种引擎。</p>
 */
final class EsClientFactory {

    private EsClientFactory() {}

    /**
     * 构建 ES 客户端。
     *
     * <p><b>超时必须显式设置</b>：客户端的默认 socket 超时是 30s，
     * 与整个搜索接口的目标延迟（毫秒级）严重不匹配 —— ES 一旦变慢而不是直接拒绝，
     * 请求线程会被挂住几十秒，进而耗尽 Tomcat 线程池。这里的 2s/5s 是
     * 「宁可判定失败并降级到 MySQL，也不要拖住调用方」。</p>
     *
     * <p>HttpClient 5 的语义比 4.x 更细：连接超时属于 {@code ConnectionConfig}，
     * 而「等响应」用 {@code RequestConfig.responseTimeout}（4.x 的 socketTimeout 等价物）。</p>
     *
     * @param uris 多个节点用英文逗号分隔
     */
    static ElasticsearchClient build(String uris, int connectTimeoutMs, int socketTimeoutMs) {
        List<HttpHost> hosts = new ArrayList<>();
        for (String uri : uris.split(",")) {
            String trimmed = uri.trim();
            if (!trimmed.isEmpty()) {
                hosts.add(HttpHost.create(URI.create(withScheme(trimmed))));
            }
        }
        Timeout connectTimeout = Timeout.ofMilliseconds(connectTimeoutMs);
        Timeout responseTimeout = Timeout.ofMilliseconds(socketTimeoutMs);
        Rest5Client restClient = Rest5Client.builder(hosts.toArray(new HttpHost[0]))
                .setConnectionConfigCallback(config -> config.setConnectTimeout(connectTimeout))
                .setRequestConfigCallback(config -> config.setResponseTimeout(responseTimeout))
                .build();
        return new ElasticsearchClient(new Rest5ClientTransport(restClient, new Jackson3JsonpMapper()));
    }

    /**
     * 补全缺失的协议前缀。
     *
     * <p>配置里写 {@code localhost:9200}（漏了 {@code http://}）很常见 —— 很多人从
     * {@code HostAndPort} 风格的示例里复制。{@code HttpHost.create(URI.create("localhost:9200"))}
     * 的报错是 {@code NullPointerException: Host name}，<b>完全不提「你漏了协议」</b>，
     * 排查成本极高（本项目在测试环境就踩过一次）。这里显式补 {@code http://}：
     * 对合法配置零影响，对漏写协议的情况给出可用行为。</p>
     */
    private static String withScheme(String uri) {
        return uri.contains("://") ? uri : "http://" + uri;
    }
}
