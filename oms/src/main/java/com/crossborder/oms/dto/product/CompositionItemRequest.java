package com.crossborder.oms.dto.product;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 판매상품 구성 1행.
 *
 * @param gift 구성 고정 사은품 (재고 차감 대상, 매출 제외)
 */
public record CompositionItemRequest(@NotNull Long productId, @Min(1) int quantity, boolean gift) {
}
