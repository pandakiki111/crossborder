package com.crossborder.infra.jpa;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 현재 요청/작업의 수행자 id 보관소. AuditorAware가 여기서 created_user_id / updated_user_id 값을 꺼낸다.
 * 비어 있으면 SYSTEM 계정으로 기록된다 (JpaAuditingConfig).
 * <p>
 * - 웹 요청: 인증 필터에서 set, 요청 종료 시 반드시 clear
 * - 배치·주문 수집 등 시스템 작업: runAs(SYSTEM 계정 id, ...)로 감싸서 실행
 */
public final class AuditorContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private AuditorContext() {
    }

    public static void set(Long userId) {
        CURRENT.set(userId);
    }

    public static Optional<Long> get() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void runAs(Long userId, Runnable task) {
        callAs(userId, () -> {
            task.run();
            return null;
        });
    }

    /**
     * 지정한 수행자로 작업을 실행하고, 끝나면 이전 수행자로 되돌린다.
     */
    public static <T> T callAs(Long userId, Supplier<T> task) {
        Long previous = CURRENT.get();
        CURRENT.set(userId);
        try {
            return task.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
