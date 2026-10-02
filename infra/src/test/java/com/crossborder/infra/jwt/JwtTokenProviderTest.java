package com.crossborder.infra.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crossborder.common.entity.organization.UserRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

    private static final String SECRET = base64("0123456789abcdef0123456789abcdef");
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    private final JwtProperties properties = new JwtProperties(SECRET, "crossborder-auth", Duration.ofMinutes(30));
    private final JwtTokenProvider provider = new JwtTokenProvider(properties, fixed(NOW));

    @Test
    void 발급한_토큰을_검증하면_같은_payload를_돌려준다() {
        TokenPayload payload = new TokenPayload(10L, UserRole.BRAND_STAFF, 2L, 3L);

        IssuedToken token = provider.issue(payload);

        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(provider.verify(token.value())).isEqualTo(payload);
    }

    @Test
    void 소속이_없는_사용자는_null로_복원된다() {
        TokenPayload payload = new TokenPayload(1L, UserRole.ADMIN, null, null);

        assertThat(provider.verify(provider.issue(payload).value())).isEqualTo(payload);
    }

    @Test
    void 만료된_토큰은_EXPIRED() {
        String token = provider.issue(new TokenPayload(1L, UserRole.WORKER, 2L, null)).value();
        JwtTokenProvider later = new JwtTokenProvider(properties, fixed(NOW.plus(Duration.ofMinutes(31))));

        assertThatThrownBy(() -> later.verify(token))
                .isInstanceOfSatisfying(InvalidTokenException.class,
                        e -> assertThat(e.getReason()).isEqualTo(InvalidTokenException.Reason.EXPIRED));
    }

    @Test
    void 다른_키로_서명된_토큰은_INVALID() {
        JwtProperties other = new JwtProperties(base64("fedcba9876543210fedcba9876543210"), "crossborder-auth",
                Duration.ofMinutes(30));
        String token = new JwtTokenProvider(other, fixed(NOW)).issue(new TokenPayload(1L, UserRole.ADMIN, null, null))
                .value();

        assertInvalid(token);
    }

    @Test
    void 발급자가_다르면_INVALID() {
        JwtProperties other = new JwtProperties(SECRET, "someone-else", Duration.ofMinutes(30));
        String token = new JwtTokenProvider(other, fixed(NOW)).issue(new TokenPayload(1L, UserRole.ADMIN, null, null))
                .value();

        assertInvalid(token);
    }

    @Test
    void 형식이_깨진_토큰은_INVALID() {
        assertInvalid("not-a-jwt");
        assertInvalid("");
    }

    @Test
    void 숫자가_아닌_소속_클레임은_INVALID() {
        String token = Jwts.builder()
                .issuer("crossborder-auth")
                .subject("1")
                .expiration(Date.from(NOW.plus(Duration.ofMinutes(30))))
                .claim("role", UserRole.BRAND_STAFF.name())
                .claim("companyId", "2")
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .compact();

        assertInvalid(token);
    }

    @Test
    void 짧은_secret은_기동_시점에_거부된다() {
        JwtProperties weak = new JwtProperties(base64("short"), "crossborder-auth", Duration.ofMinutes(30));

        assertThatThrownBy(() -> new JwtTokenProvider(weak, fixed(NOW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256bit");
    }

    private void assertInvalid(String token) {
        assertThatThrownBy(() -> provider.verify(token))
                .isInstanceOfSatisfying(InvalidTokenException.class,
                        e -> assertThat(e.getReason()).isEqualTo(InvalidTokenException.Reason.INVALID));
    }

    private static Clock fixed(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static String base64(String raw) {
        return Base64.getEncoder().encodeToString(raw.getBytes());
    }
}
