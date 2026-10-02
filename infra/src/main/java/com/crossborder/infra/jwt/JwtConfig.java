package com.crossborder.infra.jwt;

import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * crossborder.jwt.secret이 설정된 모듈(auth/oms/cbt)에만 JwtTokenProvider를 등록한다.
 * batch처럼 토큰을 다루지 않는 모듈은 설정이 없으므로 빈이 생기지 않는다.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "crossborder.jwt", name = "secret")
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    @Bean
    @ConditionalOnMissingBean
    public JwtTokenProvider jwtTokenProvider(JwtProperties properties) {
        return new JwtTokenProvider(properties, Clock.systemUTC());
    }
}
