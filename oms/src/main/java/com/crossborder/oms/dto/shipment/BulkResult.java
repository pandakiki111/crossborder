package com.crossborder.oms.dto.shipment;

import java.util.List;

/**
 * 일괄 처리 요약. 대상마다 독립 트랜잭션이라 일부 성공이 있을 수 있다.
 *
 * @param <T> 성공 시 결과
 */
public record BulkResult<T>(int successCount, int failureCount, List<Entry<T>> results) {

    /**
     * @param error  실패 시 분류 (BAD_REQUEST / FORBIDDEN / NOT_FOUND / CONFLICT)
     * @param reason 실패 사유
     */
    public record Entry<T>(Long id, boolean success, T result, String error, String reason) {
    }

    public static <T> BulkResult<T> of(List<Entry<T>> results) {
        int success = (int) results.stream().filter(Entry::success).count();
        return new BulkResult<>(success, results.size() - success, results);
    }
}
