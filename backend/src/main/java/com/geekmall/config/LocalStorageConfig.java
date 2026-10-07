package com.geekmall.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地磁盘存储的静态资源映射：把 /uploads/** 指向 mall.storage.local-dir。
 *
 * <p>仅在 {@code mall.storage.type=local} 时生效，避免使用对象存储时多暴露一个入口。</p>
 */
@Configuration
@ConditionalOnProperty(name = "mall.storage.type", havingValue = "local")
public class LocalStorageConfig implements WebMvcConfigurer {

    @Value("${mall.storage.local-dir:./uploads}")
    private String localDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path basePath = Paths.get(localDir).toAbsolutePath().normalize();
        String location = basePath.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}
