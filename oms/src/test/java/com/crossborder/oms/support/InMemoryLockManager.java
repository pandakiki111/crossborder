package com.crossborder.oms.support;

import com.crossborder.infra.lock.DistributedLockManager;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Redis 없이 도는 슬라이스 테스트용 락 (단일 JVM). 락 동작 자체는 StockAllocationTest가 실제 Redisson으로 검증한다.
 */
public class InMemoryLockManager implements DistributedLockManager {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T executeWithLocks(Collection<String> keys, Supplier<T> action) {
        List<ReentrantLock> acquired = acquire(keys);
        try {
            return action.get();
        } finally {
            acquired.forEach(ReentrantLock::unlock);
        }
    }

    @Override
    public void lockUntilTransactionEnd(Collection<String> keys) {
        List<ReentrantLock> acquired = acquire(keys);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                acquired.forEach(ReentrantLock::unlock);
            }
        });
    }

    private List<ReentrantLock> acquire(Collection<String> keys) {
        List<ReentrantLock> sorted = new TreeSet<>(keys).stream()
                .map(key -> locks.computeIfAbsent(key, k -> new ReentrantLock()))
                .toList();
        sorted.forEach(ReentrantLock::lock);
        return sorted;
    }
}
