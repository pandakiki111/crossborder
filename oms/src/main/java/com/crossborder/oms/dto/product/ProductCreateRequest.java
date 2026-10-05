package com.crossborder.oms.dto.product;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 제품 등록. sku는 전역 유니크, 생성 후 불변.
 *
 * @param customsUnitQty 재고 1단위당 통관 환산 수량 (비우면 1, 10매입 박스 = 10)
 */
public record ProductCreateRequest(
        @NotNull Long brandId,
        @NotBlank @Size(max = 50) String sku,
        @NotBlank @Size(max = 300) String name,
        @NotBlank @Size(max = 300) String nameEng,
        Long customsCategoryId,
        @Min(1) Integer customsUnitQty,
        @Size(max = 20) String hsCode,
        @DecimalMin("0") BigDecimal unitPrice,
        @Size(min = 3, max = 3) String currency,
        @Size(max = 50) String barcode,
        BigDecimal weightG,
        BigDecimal widthCm,
        BigDecimal lengthCm,
        BigDecimal heightCm,
        @Size(max = 50) String origin
) {
}
