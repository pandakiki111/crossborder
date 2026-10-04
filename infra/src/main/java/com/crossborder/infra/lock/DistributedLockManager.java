package com.crossborder.infra.lock;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * 분산 락. 구현(Redisson)은 infra 안에 감추고 실행 모듈은 이 인터페이스만 쓴다.
 * <p>
 * 여러 키는 정렬·중복 제거한 순서로 한 번에(멀티락) 획득한다 — 호출 측 순서와 무관하게 전역 순서가 같아 데드락이 없다.
 * 대기 시간 안에 전부 얻지 못하면 {@link LockAcquisitionException}.
 */
public interface DistributedLockManager {

    /**
     * keys를 잠그고 action을 실행한 뒤 해제한다. 트랜잭션은 action 안에서 시작·커밋해야 커밋 후 해제가 보장된다.
     */
    <T> T executeWithLocks(Collection<String> keys, Supplier<T> action);

    /**
     * keys를 잠그고 현재 트랜잭션이 끝날 때(커밋·롤백 후) 해제한다. 이미 열린 트랜잭션 안에서 잠가야 할 때 쓴다.
     *
     * @throws IllegalStateException 트랜잭션 동기화가 활성화되지 않은 곳에서 호출
     */
    void lockUntilTransactionEnd(Collection<String> keys);
}
