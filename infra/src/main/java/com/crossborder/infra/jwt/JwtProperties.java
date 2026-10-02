package com.crossborder.infra.jwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param secret         HS256 서명 키 (Base64, 디코딩 후 256bit 이상). auth·oms·cbt가 같은 값을 쓴다
 * @param issuer         발급자 (iss). 검증 시 일치 여부를 확인한다
 * @param accessTokenTtl 액세스 토큰 유효 기간
 */
@ConfigurationProperties(prefix = "crossborder.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("crossborder-auth") String issuer,
        @DefaultValue("30m") Duration accessTokenTtl
) {
}
