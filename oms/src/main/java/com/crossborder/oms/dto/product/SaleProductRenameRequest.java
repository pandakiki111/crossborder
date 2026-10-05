package com.crossborder.oms.dto.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 판매상품 수정은 이름만 (code 불변, 구성은 구성 변경·리뉴얼로) */
public record SaleProductRenameRequest(@NotBlank @Size(max = 300) String name) {
}
