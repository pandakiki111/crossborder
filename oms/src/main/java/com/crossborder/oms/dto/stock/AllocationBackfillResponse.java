package com.crossborder.oms.dto.stock;

import java.util.List;

/**
 * 소급 할당 결과 요약. processed = allocated + skipped + failed + anomalies.
 *
 * @param processed       대상(allocated_at IS NULL AND status != 'CANCELED')으로 조회한 주문 수
 * @param allocated       이번 실행에서 할당하고 allocated_at을 기록한 주문 수
 * @param skipped         할당하지 않은 주문 수 — 매핑안됨(매핑 완료 시 자동 할당)이거나, 처리 직전 다른 경로가 먼저 할당한 주문
 * @param failed          락 타임아웃 등으로 실패한 주문 수 (다시 실행하면 이어서 처리된다)
 * @param anomalies       이상 데이터: 미할당 상태로 DELIVERED가 된 주문 수. 재고 정합 때문에 할당하지 않는다 (운영자 확인)
 * @param anomalyOrderIds 이상 주문 id (최대 100개)
 */
public record AllocationBackfillResponse(int processed, int allocated, int skipped, int failed,
                                         int anomalies, List<Long> anomalyOrderIds) {
}
