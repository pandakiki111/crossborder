package com.crossborder.oms.dto.shipment;

import java.util.List;

/**
 * 일괄 출고지시 — 작업 단위는 주문이 아니라 회차
 */
public record BulkInstructRequest(List<Long> shipmentIds) {
}
