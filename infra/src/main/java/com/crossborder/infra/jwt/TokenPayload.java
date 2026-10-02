package com.crossborder.infra.jwt;

import com.crossborder.common.entity.organization.UserRole;
import java.util.Objects;

/**
 * 액세스 토큰에 담기는 인증 주체 정보. 발급(auth)과 검증(oms/cbt)이 이 타입으로만 주고받는다.
 *
 * @param companyId 소속 회사 (ADMIN·WORKER 등 회사 미소속이면 null)
 * @param brandId   소속 브랜드 (BRAND_STAFF가 아니면 null)
 */
public record TokenPayload(Long userId, UserRole role, Long companyId, Long brandId) {

    public TokenPayload {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
    }
}
