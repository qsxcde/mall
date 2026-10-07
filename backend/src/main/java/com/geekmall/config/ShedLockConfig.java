package com.geekmall.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 定时任务分布式锁配置。
 *
 * <p>单体单实例时它不是必需的，但一旦多实例部署，订单超时取消等任务会被重复执行。
 * 用 ShedLock + Redis 即可做到「同一时刻集群内只有一个实例执行」，且不引入额外的中间件。</p>
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory);
    }
}
