package com.crossborder.oms.dto.product;

/**
 * 매핑 등록·재지정 결과
 *
 * @param mappingId           현재 유효한 매핑 행 id (재지정이면 새로 생긴 행)
 * @param resolvedItemCount   이 매핑으로 확정된 매핑안됨 주문 항목 수
 * @param completedOrderCount 매핑안됨이 모두 해소된 주문 수
 */
public record ChannelMappingChangeResponse(Long mappingId, int resolvedItemCount, int completedOrderCount) {
}
