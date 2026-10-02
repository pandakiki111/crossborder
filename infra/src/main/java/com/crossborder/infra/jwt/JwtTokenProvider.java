package com.crossborder.infra.jwt;

import com.crossborder.common.entity.organization.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

/**
 * 액세스 토큰 발급/검증. 공개 API는 TokenPayload·IssuedToken·InvalidTokenException만 쓰고 jjwt 타입은 노출하지 않는다.
 */
public class JwtTokenProvider {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_COMPANY_ID = "companyId";
    private static final String CLAIM_BRAND_ID = "brandId";

    private final SecretKey key;
    private final JwtProperties properties;
    private final Clock clock;
    private final JwtParser parser;

    public JwtTokenProvider(JwtProperties properties, Clock clock) {
        this.key = toKey(properties.secret());
        this.properties = properties;
        this.clock = clock;
        this.parser = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    public IssuedToken issue(TokenPayload payload) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        String token = Jwts.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(payload.userId()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim(CLAIM_ROLE, payload.role().name())
                .claim(CLAIM_COMPANY_ID, payload.companyId())
                .claim(CLAIM_BRAND_ID, payload.brandId())
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /**
     * @throws InvalidTokenException 만료(EXPIRED) 또는 서명·형식·발급자·클레임 오류(INVALID)
     */
    public TokenPayload verify(String token) {
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            return new TokenPayload(
                    Long.valueOf(claims.getSubject()),
                    UserRole.valueOf(claims.get(CLAIM_ROLE, String.class)),
                    // 타입이 맞지 않으면 RequiredTypeException(JwtException)으로 INVALID 처리된다
                    claims.get(CLAIM_COMPANY_ID, Long.class),
                    claims.get(CLAIM_BRAND_ID, Long.class));
        } catch (ExpiredJwtException e) {
            throw new InvalidTokenException(InvalidTokenException.Reason.EXPIRED, "토큰이 만료되었습니다", e);
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            // IllegalArgumentException: 빈 토큰, 숫자가 아닌 sub, 알 수 없는 role / NPE: 필수 클레임 누락
            throw new InvalidTokenException(InvalidTokenException.Reason.INVALID, "유효하지 않은 토큰입니다", e);
        }
    }

    private static SecretKey toKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("crossborder.jwt.secret이 설정되지 않았습니다");
        }
        try {
            return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        } catch (WeakKeyException e) {
            throw new IllegalStateException("crossborder.jwt.secret은 디코딩 후 256bit 이상이어야 합니다", e);
        }
    }
}
