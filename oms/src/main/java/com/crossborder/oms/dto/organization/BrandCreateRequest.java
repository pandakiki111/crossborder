package com.crossborder.oms.dto.organization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BrandCreateRequest(@NotNull Long companyId, @NotBlank @Size(max = 100) String name) {
}
