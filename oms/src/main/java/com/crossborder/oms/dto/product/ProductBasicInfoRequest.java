package com.crossborder.oms.dto.product;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Product.changeBasicInfo 단위 (전체 교체) */
public record ProductBasicInfoRequest(@NotBlank @Size(max = 300) String name, @NotBlank @Size(max = 300) String nameEng,
                                      @Size(max = 50) String barcode, @DecimalMin("0") BigDecimal unitPrice,
                                      @Size(min = 3, max = 3) String currency) {
}
