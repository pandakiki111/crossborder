package com.crossborder.oms.dto.organization;

import com.crossborder.common.entity.ActiveStatus;
import com.crossborder.common.entity.organization.Brand;
import java.time.LocalDateTime;

/**
 * @param terminatedAt 계약종료 시각 (값이 있으면 재활성화 불가)
 */
public record BrandResponse(Long id, Long companyId, String name, ActiveStatus status, LocalDateTime terminatedAt) {

    public static BrandResponse from(Brand brand) {
        return new BrandResponse(brand.getId(), brand.getCompanyId(), brand.getName(), brand.getStatus(),
                brand.getDeletedAt());
    }
}
