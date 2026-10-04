package com.crossborder.oms.dto.order;

import java.util.List;

/**
 * 자동 분리 제안 (브랜드 분리·통관 분류 수량 한도 기준). 저장하지 않는다.
 *
 * @param shipments 제안 회차 — 그대로 OrderSplitRequest로 보내면 분리 확정
 * @param notes     판정 근거 (예: "SHEET_MASK 합산 150 > 한도 120")
 */
public record SplitPreviewResponse(
        List<OrderSplitRequest.Round> shipments,
        List<String> notes
) {
}
