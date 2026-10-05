package com.crossborder.oms.dto.product;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Product.changeCustomsInfo + changeCustomsUnitQty 단위 (전체 교체) */
public record ProductCustomsInfoRequest(Long customsCategoryId, @Size(max = 20) String hsCode,
                                        @Size(max = 50) String origin, @NotNull @Min(1) Integer customsUnitQty) {
}
