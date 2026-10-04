package com.crossborder.oms.dto.order;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 건별 사은품 추가 (order_items GIFT_PRODUCT, 단가 0)
 */
public record GiftAddRequest(
        @NotNull Long productId,
        @Positive int quantity
) {
}
