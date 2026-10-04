package com.crossborder.infra.lock;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param waitTime  락 획득 대기 시간. 넘기면 LockAcquisitionException
 * @param leaseTime 락 자동 해제 시간 (보유 프로세스가 죽어도 이 시간 뒤 풀린다). 작업 시간보다 충분히 길게.
 *                  명시하면 Redisson watchdog 자동 연장이 꺼진다. 기본 30s 근거는 docs/design-decisions.md §1
 */
@ConfigurationProperties(prefix = "crossborder.lock")
public record LockProperties(
        @DefaultValue("3s") Duration waitTime,
        @DefaultValue("30s") Duration leaseTime
) {
}
