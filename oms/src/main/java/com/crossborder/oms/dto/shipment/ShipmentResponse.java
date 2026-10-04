package com.crossborder.oms.dto.shipment;

import com.crossborder.common.entity.order.ShipmentStatus;
import com.crossborder.common.entity.order.SplitReason;
import java.math.BigDecimal;
import java.util.List;

/**
 * 출고 회차.
 *
 * @param splitReason 단일 회차면 null. 분류 한도 분할 CUSTOMS_LIMIT, 그 외 멀티브랜드 BRAND_SPLIT
 * @param totalAmount 통관 신고 참조값 = Σ(배정 항목 단가 스냅샷 × 배정 수량). 주문 금액(paid_amount)과의 합계 일치를 강제하지 않는다
 * @param warnings    판단 보조 경고 (저장하지 않고 조회 시 계산). 예: 품목당 24개 규정 근사 — 회차 전체 개수 초과
 */
public record ShipmentResponse(
        Long shipmentId,
        String shipmentNo,
        int roundNo,
        Long brandId,
        ShipmentStatus status,
        SplitReason splitReason,
        BigDecimal totalAmount,
        List<Item> items,
        List<String> warnings
) {

    public record Item(Long orderItemId, int quantity) {
    }
}
