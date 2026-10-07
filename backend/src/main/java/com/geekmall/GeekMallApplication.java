package com.geekmall;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 极客数码商城 - 后端服务启动类。
 *
 * <p>启动前请确保 MySQL / Redis 已就绪（可执行 infra/docker-compose.yml）。</p>
 */
@EnableAsync
@EnableScheduling
// 限定只扫描带 @Mapper 的接口，避免把 service 接口误注册为 Mapper
@MapperScan(basePackages = "com.geekmall", annotationClass = Mapper.class)
@SpringBootApplication
public class GeekMallApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeekMallApplication.class, args);
    }
}
