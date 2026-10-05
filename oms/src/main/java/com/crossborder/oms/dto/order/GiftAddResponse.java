package com.crossborder.oms.dto.order;

/**
 * @param orderItemId 생성된 사은품 항목 (취소는 주문 취소 API에 이 id로)
 */
public record GiftAddResponse(Long orderItemId) {
}
