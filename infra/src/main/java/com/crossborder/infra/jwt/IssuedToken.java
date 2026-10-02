package com.crossborder.infra.jwt;

import java.time.Instant;

/**
 * 발급된 액세스 토큰과 만료 시각.
 */
public record IssuedToken(String value, Instant expiresAt) {
}
