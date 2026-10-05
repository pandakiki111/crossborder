package com.crossborder.oms.dto.organization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 브랜드 수정은 이름만 (소속 회사는 바꾸지 않는다) */
public record BrandUpdateRequest(@NotBlank @Size(max = 100) String name) {
}
