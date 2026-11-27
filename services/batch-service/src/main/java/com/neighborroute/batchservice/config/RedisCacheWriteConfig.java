package com.neighborroute.batchservice.config;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration(proxyBeanMethods = false)
public class RedisCacheWriteConfig {

    @Bean
    public RedisCacheManagerBuilderCustomizer immediateRedisWrites(
            RedisConnectionFactory connectionFactory
    ) {
        return builder -> builder.cacheWriter(
                RedisCacheWriter.create(
                        connectionFactory,
                        configurer -> configurer.immediateWrites()
                )
        );
    }
}
