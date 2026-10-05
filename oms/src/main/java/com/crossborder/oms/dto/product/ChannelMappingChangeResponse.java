package com.crossborder.oms.dto.product;

/**
 * 매핑 등록·재지정 결과
 *
 * @param mappingId           현재 유효한 매핑 행 id (재지정이면 새로 생긴 행)
 * @param resolvedItemCount   이 매핑으로 확정된 매핑안됨 주문 항목 수
 * @param completedOrderCount 매핑안됨이 모두 해소된 주문 수
 * @param giftEventNotice     확정된 항목이 있으면 사은품 이벤트 기평가 안내 (매핑 후 재평가하지 않는다), 없으면 null
 */
public record ChannelMappingChangeResponse(Long mappingId, int resolvedItemCount, int completedOrderCount,
                                           String giftEventNotice) {

    public static final String GIFT_EVENT_NOTICE = "확정된 주문은 등록 시 사은품 이벤트 판정을 이미 마쳤습니다 "
            + "(매핑안됨 항목은 조건에서 빠진 채로 판정됨). 매핑 후 다시 판정하지 않으니 필요하면 수동 증정하세요.";

    public static ChannelMappingChangeResponse of(Long mappingId, int resolvedItemCount, int completedOrderCount) {
        return new ChannelMappingChangeResponse(mappingId, resolvedItemCount, completedOrderCount,
                resolvedItemCount > 0 ? GIFT_EVENT_NOTICE : null);
    }
}
