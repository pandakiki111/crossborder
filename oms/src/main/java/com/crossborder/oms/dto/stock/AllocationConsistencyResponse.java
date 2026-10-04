package com.crossborder.oms.dto.stock;

import java.util.List;

/**
 * 등록-할당 정합 검증 결과.
 *
 * @param mismatches         기대 할당(할당 완료 주문의 유효 항목 전개 합)과 products.allocated_stock이 다른 제품
 * @param unallocatedOrders  할당 대상인데 아직 미할당인 주문 수 (소급 할당 대상)
 * @param mappingPendingOrders 매핑안됨이라 할당 대기 중인 주문 수 (정상 — 매핑 완료 시 할당)
 */
public record AllocationConsistencyResponse(
        int checkedProducts,
        List<Mismatch> mismatches,
        long unallocatedOrders,
        long mappingPendingOrders
) {

    public record Mismatch(Long productId, String sku, int expectedAllocated, int actualAllocated) {
    }
}
