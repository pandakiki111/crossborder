package com.crossborder.infra.lock;

import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * RedissonClient가 있는 모듈에 DistributedLockManager를 등록한다. 실행 모듈은 RedissonClient를 직접 쓰지 않는다 (§1).
 */
@AutoConfiguration(afterName = "org.redisson.spring.starter.RedissonAutoConfigurationV2")
@ConditionalOnBean(RedissonClient.class)
@EnableConfigurationProperties(LockProperties.class)
public class LockConfig {

    @Bean
    @ConditionalOnMissingBean
    public DistributedLockManager distributedLockManager(RedissonClient redissonClient, LockProperties properties) {
        return new RedissonDistributedLockManager(redissonClient, properties);
    }
}
