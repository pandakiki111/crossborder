package com.crossborder.infra.jpa;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 실행 모듈 공용 JPAQueryFactory. Q클래스는 엔티티가 있는 common에서 생성된다.
 */
@AutoConfiguration
public class QuerydslConfig {

    @Bean
    @ConditionalOnMissingBean
    public JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
