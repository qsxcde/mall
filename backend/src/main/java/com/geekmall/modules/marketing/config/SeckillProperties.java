package com.geekmall.modules.marketing.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
         * 是否在消费端启用「一次拉取 N 条、一个事务批量落库」。
         *
         * <p>默认关闭：批量落库只改变消费端实现，不改接口契约，但仍需先小流量验证。
         * 关闭时完全走原来的逐条事务路径，行为零变化。</p>
         */
        private boolean batchEnabled = false;

        /**
         * 消费者线程数。
         *
         * <p>⚠️ <b>必须以数据库写能力为上限</b>。消费者开得远超 DB 承载，只是把雪崩点
         * 从 Tomcat 平移到数据库 —— 削峰不提升吞吐，它只是把脉冲摊平。</p>
         *
         * <p>取 8 的依据：批量参数交叉重复实测（2026-10-24）显示「8 线程 / 批 128」
         * 能把 200 单收敛到 2 个事务、逐条回退压到 10 单，行锁时长比 64 线程低两个数量级。</p>
         */
        private int consumerThreads = 8;

        /** 单次拉取条数；批量落库开启时也是单批订单数。128 为实测粒度，见上。 */
        private int batchSize = 128;

        /**
         * 攒批窗口：一次 poll 只拿到少量消息时，在窗口内继续拉取以凑满一批。
         *
         * <p>消费线程多于消息数时，每次 XREADGROUP 可能只取到 1 条，导致大量订单退回逐条事务。
         * 窗口只对<b>非空</b>批次生效，且每轮最多补拉 2ms，避免低流量下人为增加排队延迟。
         * 设为 0 关闭攒批，恢复「poll 到什么就处理什么」的旧行为。</p>
         */
        private Duration gatherWindow = Duration.ofMillis(10);

        /**
         * 单条消息允许的最大投递次数。
         *
         * <p>超过后转入<b>死信</b>：回补预扣 + 写失败终态 + 丢弃，不再无限重投。
         * 否则系统性故障（如 DB 持续不可用）会让那条消息永久占用 pending，
         * 用户也永远停在「排队中」——既占资源，又给不出确定结果。</p>
         */
        private int maxDeliveries = 3;

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
