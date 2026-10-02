package com.crossborder.oms.security;

import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.infra.jwt.TokenPayload;

/**
 * 인증된 요청의 principal. 컨트롤러에서 @AuthenticationPrincipal로 받는다.
 *
 * @param companyId 소속 회사 (회사 미소속이면 null)
 * @param brandId   소속 브랜드 (BRAND_STAFF가 아니면 null)
 */
public record AuthenticatedUser(Long userId, UserRole role, Long companyId, Long brandId) {

    public static AuthenticatedUser from(TokenPayload payload) {
        return new AuthenticatedUser(payload.userId(), payload.role(), payload.companyId(), payload.brandId());
    }
}
