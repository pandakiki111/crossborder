package com.crossborder.infra.jpa;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

/**
 * common 엔티티 스캔. 실행 모듈마다 @EntityScan을 반복하지 않도록 infra가 제공한다.
 * <p>
 * 실행 모듈에 @EntityScan을 따로 두면 이 범위를 덮어쓰므로 두지 않는다.
 */
@AutoConfiguration(before = HibernateJpaAutoConfiguration.class)
@EntityScan("com.crossborder.common.entity")
public class EntityScanConfig {
}
