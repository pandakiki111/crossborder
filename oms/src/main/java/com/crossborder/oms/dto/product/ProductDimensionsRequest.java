package com.crossborder.oms.dto.product;

import java.math.BigDecimal;

/** Product.changeDimensions 단위 (전체 교체) */
public record ProductDimensionsRequest(BigDecimal weightG, BigDecimal widthCm, BigDecimal lengthCm,
                                       BigDecimal heightCm) {
}
