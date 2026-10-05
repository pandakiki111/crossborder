package com.crossborder.oms.dto.organization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompanyRequest(@NotBlank @Size(max = 100) String name, @Size(max = 20) String businessNo) {
}
