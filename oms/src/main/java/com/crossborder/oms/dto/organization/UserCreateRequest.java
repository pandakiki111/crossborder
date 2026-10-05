package com.crossborder.oms.dto.organization;

import com.crossborder.common.entity.organization.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 사용자 등록 (ADMIN). role별 소속: ADMIN·WORKER 없음 / COMPANY_STAFF companyId / BRAND_STAFF brandId (회사는 브랜드에서).
 *
 * @param loginId  WORKER 작업자코드 (관리자군은 무시 — login_id = 이메일)
 * @param password 초기 비밀번호 (BCrypt로만 저장)
 */
public record UserCreateRequest(
        @NotNull UserRole role,
        @Size(max = 50) String loginId,
        @Email @Size(max = 50) String email,
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Size(min = 8, max = 100) String password,
        Long companyId,
        Long brandId
) {
}
