package com.crossborder.oms.dto.product;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 리뉴얼: 새 판매상품(새 code, 새 구성)으로 복사 생성 → 현재 채널 매핑 일괄 재지정 → 구 상품 INACTIVE.
 *
 * @param name 비우면 구 상품 이름 그대로
 */
public record SaleProductRenewRequest(@NotBlank @Size(max = 100) String code, @Size(max = 300) String name,
                                      @Valid @NotEmpty List<CompositionItemRequest> items) {
}
