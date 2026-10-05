package com.crossborder.oms.dto.product;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 판매상품 등록 (구성 포함, 원자적). code는 브랜드 안에서 유니크, 생성 후 불변.
 */
public record SaleProductCreateRequest(@NotNull Long brandId, @NotBlank @Size(max = 300) String name,
                                       @NotBlank @Size(max = 100) String code,
                                       @Valid @NotEmpty List<CompositionItemRequest> items) {
}
