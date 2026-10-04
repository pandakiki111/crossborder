package com.crossborder.infra.jpa;

import java.util.List;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScanPackages;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;

/**
 * 엔티티 스캔. 실행 모듈마다 @EntityScan을 반복하지 않도록 infra가 제공하되, 스캔 범위는 모듈이 설정으로 선언한다.
 * <ul>
 *   <li>{@code crossborder.jpa.entity-packages} 미설정: common 엔티티 전체 (oms·auth·batch)</li>
 *   <li>cbt: 자기 엔티티 패키지만 선언해 oms 엔티티를 로드하지 않는다 (별도 DB라 validate가 깨진다)</li>
 * </ul>
 * 실행 모듈에 @EntityScan을 따로 두면 이 범위를 덮어쓰므로 두지 않는다.
 */
@AutoConfiguration(before = HibernateJpaAutoConfiguration.class)
@Import(EntityScanConfig.Registrar.class)
public class EntityScanConfig {

    static final String PROPERTY = "crossborder.jpa.entity-packages";
    static final String DEFAULT_PACKAGE = "com.crossborder.common.entity";

    static class Registrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            List<String> packages = Binder.get(environment)
                    .bind(PROPERTY, Bindable.listOf(String.class))
                    .orElse(List.of(DEFAULT_PACKAGE));
            // 빈 값이면 스프링 부트가 애플리케이션 패키지 스캔으로 조용히 폴백하므로 명시를 강제한다
            if (packages.isEmpty()) {
                throw new IllegalStateException(PROPERTY + "가 비어 있습니다. 엔티티 패키지를 명시하세요.");
            }
            EntityScanPackages.register(registry, packages);
        }
    }
}
