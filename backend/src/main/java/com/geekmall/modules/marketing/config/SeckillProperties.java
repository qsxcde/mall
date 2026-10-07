package com.geekmall.modules.marketing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 秒杀配置（{@code mall.seckill.*}）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.seckill")
public class SeckillProperties {

    private Async async = new Async();

    /**
     * 削峰（异步落库）相关配置。
     *
     * <p><b>默认关闭</b>：异步化改变了接口契约（从返回订单号变成返回排队凭证），
     * 前端需要配套改造。默认关闭可以保证「没改前端时行为完全不变」，
     * 也方便用同一个压测脚本对比开关前后的延迟与吞吐。</p>
     */
    @Data
    public static class Async {

        /** 是否启用「预扣成功即入队、后台异步落库」。 */
        private boolean enabled = false;

        /**
         * 消费者线程数。
         *
         * <p>⚠️ <b>必须以数据库写能力为上限</b>。消费者开得远超 DB 承载，只是把雪崩点
         * 从 Tomcat 平移到数据库 —— 削峰不提升吞吐，它只是把脉冲摊平。</p>
         */
        private int consumerThreads = 4;

        /** 单次拉取条数。 */
        private int batchSize = 16;

        /** 无消息时的阻塞等待时长。 */
        private Duration block = Duration.ofSeconds(2);

        /** 未确认消息闲置多久后可被回收重投（消费者崩溃兜底）。 */
        private Duration reclaimMinIdle = Duration.ofMinutes(1);

        /** 回收扫描间隔。 */
        private Duration reclaimInterval = Duration.ofSeconds(30);

        /** 抢购结果保留时长（前端轮询窗口）。 */
        private Duration resultTtl = Duration.ofMinutes(30);

        /** 消费者标识；留空则自动生成，多实例部署时应显式配置以保证组内唯一。 */
        private String consumerName = "";

        /** 消息流最大长度（近似裁剪），防止 Redis 内存无限增长。 */
        private long maxStreamLength = 100_000L;
    }
}
