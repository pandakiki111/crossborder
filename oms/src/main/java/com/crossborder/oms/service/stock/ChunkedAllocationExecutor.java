package com.crossborder.oms.service.stock;

import com.crossborder.infra.lock.DistributedLockManager;
import com.crossborder.infra.lock.LockAcquisitionException;
import com.crossborder.oms.service.support.InClause;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 묶음 할당 실행기. 등록(시딩)과 소급 할당이 같은 실행 규칙을 쓴다.
 * <ol>
 *   <li>CHUNK_SIZE(500) 주문 단위로 묶는다</li>
 *   <li>묶음의 제품별 할당 수량을 합산해 제품 락(정렬 멀티락)을 한 번 잡는다</li>
 *   <li>락 안에서 새 트랜잭션으로 작업(INSERT·할당·allocated_at 기록 등)을 실행하고 커밋 후 락을 해제한다</li>
 *   <li>묶음이 락 타임아웃·DB 오류로 실패하면 그 묶음만 주문 단위로 다시 실행해 원인 주문만 실패시킨다</li>
 * </ol>
 * 락 키 공간은 StockAllocator.lockKeys(stock:product:{productId}) 하나로, 취소·매핑 완료 할당과 같다.
 */
@Component
public class ChunkedAllocationExecutor {

    public static final int CHUNK_SIZE = 500;

    private static final Logger log = LoggerFactory.getLogger(ChunkedAllocationExecutor.class);

    private final DistributedLockManager lockManager;
    private final TransactionTemplate transaction;

    public ChunkedAllocationExecutor(DistributedLockManager lockManager, PlatformTransactionManager transactionManager) {
        this.lockManager = lockManager;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @param workCount 성공한 묶음·단위의 work 반환값 합
     * @param failures  주문 단위 재시도에서도 실패한 단위와 원인 (단위 동일성 기준)
     */
    public record Outcome<T>(int workCount, Map<T, RuntimeException> failures) {
    }

    /**
     * @param allocationOf 단위의 제품별 할당 수량 (락 키 산정용, 할당하지 않는 단위는 null)
     * @param work         트랜잭션 안에서 실행할 작업. 반환값은 집계용 (예: 할당한 주문 수)
     */
    public <T> Outcome<T> execute(List<T> units, Function<T, Map<Long, Integer>> allocationOf,
                                  ToIntFunction<List<T>> work) {
        int count = 0;
        Map<T, RuntimeException> failures = new IdentityHashMap<>();
        for (List<T> chunk : InClause.partition(units, CHUNK_SIZE)) {
            try {
                count += run(chunk, allocationOf, work);
            } catch (DataAccessException | LockAcquisitionException e) {
                log.warn("묶음 할당 실패, 주문 단위로 재시도: size={}, cause={}", chunk.size(),
                        NestedExceptionUtils.getMostSpecificCause(e).getMessage());
                for (T unit : chunk) {
                    try {
                        count += run(List.of(unit), allocationOf, work);
                    } catch (DataAccessException | LockAcquisitionException single) {
                        failures.put(unit, single);
                    }
                }
            }
        }
        return new Outcome<>(count, failures);
    }

    private <T> int run(List<T> chunk, Function<T, Map<Long, Integer>> allocationOf, ToIntFunction<List<T>> work) {
        Map<Long, Integer> union = StockAllocator.sum(chunk.stream().map(allocationOf).filter(Objects::nonNull).toList());
        Integer result = lockManager.executeWithLocks(StockAllocator.lockKeys(union.keySet()),
                () -> transaction.execute(status -> work.applyAsInt(chunk)));
        return result == null ? 0 : result;
    }
}
