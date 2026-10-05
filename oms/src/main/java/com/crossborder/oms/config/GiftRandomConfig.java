package com.crossborder.oms.config;

import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 사은품 RANDOM 증정의 난수원. 판정 로직(GiftEventEvaluator)은 주입받은 것만 쓰므로 테스트에서 시드 고정으로 바꿔 재현한다.
 */
@Configuration
public class GiftRandomConfig {

    @Bean
    public RandomGenerator giftRandom() {
        return RandomGenerator.getDefault();
    }
}
