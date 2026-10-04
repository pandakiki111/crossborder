package com.crossborder.infra.lock;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Redisson 구현. 키가 여러 개면 RedissonMultiLock으로 전부 얻거나 전부 실패한다.
 */
class RedissonDistributedLockManager implements DistributedLockManager {

    private static final Logger log = LoggerFactory.getLogger(RedissonDistributedLockManager.class);

    private final RedissonClient redissonClient;
    private final LockProperties properties;

    RedissonDistributedLockManager(RedissonClient redissonClient, LockProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    @Override
    public <T> T executeWithLocks(Collection<String> keys, Supplier<T> action) {
        if (keys.isEmpty()) {
            return action.get();
        }
        RLock lock = acquire(keys);
        try {
            return action.get();
        } finally {
            release(lock, keys);
        }
    }

    @Override
    public void lockUntilTransactionEnd(Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("트랜잭션 밖에서는 lockUntilTransactionEnd를 쓸 수 없습니다. keys=" + keys);
        }
        RLock lock = acquire(keys);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                release(lock, keys);
            }
        });
    }

    private RLock acquire(Collection<String> keys) {
        List<String> sorted = List.copyOf(new TreeSet<>(keys));
        RLock lock = sorted.size() == 1
                ? redissonClient.getLock(sorted.getFirst())
                : redissonClient.getMultiLock(sorted.stream().map(redissonClient::getLock).toArray(RLock[]::new));
        boolean acquired;
        try {
            acquired = lock.tryLock(properties.waitTime().toMillis(), properties.leaseTime().toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException(sorted, "락 대기 중 인터럽트. keys=" + sorted, e);
        }
        if (!acquired) {
            throw new LockAcquisitionException(sorted,
                    "락을 얻지 못했습니다. waitTime=" + properties.waitTime() + ", keys=" + sorted);
        }
        return lock;
    }

    private static void release(RLock lock, Collection<String> keys) {
        try {
            lock.unlock();
        } catch (IllegalMonitorStateException e) {
            // 임대 시간이 지나 이미 풀린 경우. 작업이 leaseTime보다 길었다는 신호라 경고로 남긴다
            log.warn("락이 이미 해제됨 (leaseTime 초과 의심). keys={}", keys);
        }
    }
}
