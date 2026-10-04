package com.crossborder.oms.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 처리 시각의 단일 출처. 테스트에서 고정 시계로 바꿔 경계 시각을 결정적으로 검증한다.
 * 서버 시간대 = 마켓 주문 시각 시간대(UTC+9) 전제라 시스템 기본 시간대를 쓴다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
