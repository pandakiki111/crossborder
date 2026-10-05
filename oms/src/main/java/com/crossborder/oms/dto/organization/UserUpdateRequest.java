package com.crossborder.oms.dto.organization;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * 사용자 수정 (ADMIN). 비운 값은 바꾸지 않는다. role 변경은 미지원 (재등록).
 *
 * @param email     관리자군은 login_id도 함께 바뀐다
 * @param companyId COMPANY_STAFF만
 * @param brandId   BRAND_STAFF만 (회사도 브랜드를 따라 바뀐다)
 */
public record UserUpdateRequest(@Size(max = 50) String name, @Email @Size(max = 50) String email, Long companyId,
                                Long brandId) {
}
