package com.crossborder.infra.jpa;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화 (@CreatedDate / @LastModifiedDate / @CreatedBy / @LastModifiedBy).
 * <p>
 * 수행자는 AuditorContext에서 꺼내고, 비어 있으면 SYSTEM 계정(V4 시딩)으로 기록한다.
 */
@AutoConfiguration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    /**
     * TODO: Security 도입 후 인증 사용자 기반으로 교체하고 SYSTEM 대체를 제거할 것.
     * 대체가 남아 있으면 인증 필터에서 수행자 설정을 빠뜨린 요청도 조용히 SYSTEM으로 기록된다.
     */
    @Bean
    @ConditionalOnMissingBean(name = "auditorAware")
    public AuditorAware<Long> auditorAware(@Value("${crossborder.audit.system-user-id:1}") Long systemUserId) {
        return () -> AuditorContext.get().or(() -> Optional.of(systemUserId));
    }
}
