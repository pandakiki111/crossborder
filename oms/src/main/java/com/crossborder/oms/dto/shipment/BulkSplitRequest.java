package com.crossborder.oms.dto.shipment;

import java.util.List;

/**
 * 일괄 분리 — 화면에서 조회 후 선택한 주문 id 목록 (필터 자동 선정은 지원하지 않는다)
 */
public record BulkSplitRequest(List<Long> orderIds) {
}
