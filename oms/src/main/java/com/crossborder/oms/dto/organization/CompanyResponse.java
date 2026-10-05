package com.crossborder.oms.dto.organization;

import com.crossborder.common.entity.ActiveStatus;
import com.crossborder.common.entity.organization.Company;
import java.time.LocalDateTime;

/**
 * @param terminatedAt 폐업/계약종료 시각 (값이 있으면 재활성화 불가)
 */
public record CompanyResponse(Long id, String name, String businessNo, ActiveStatus status, LocalDateTime terminatedAt) {

    public static CompanyResponse from(Company company) {
        return new CompanyResponse(company.getId(), company.getName(), company.getBusinessNo(), company.getStatus(),
                company.getDeletedAt());
    }
}
