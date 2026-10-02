package com.crossborder.auth.dto;

import com.crossborder.common.entity.organization.UserRole;
import java.time.Instant;

public record LoginResponse(
        String accessToken,
        Instant expiresAt,
        String name,
        UserRole role
) {
}
