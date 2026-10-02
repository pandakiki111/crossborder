package com.crossborder.oms.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.infra.jpa.AuditorContext;
import com.crossborder.infra.jwt.JwtProperties;
import com.crossborder.infra.jwt.JwtTokenProvider;
import com.crossborder.infra.jwt.TokenPayload;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

    private final JwtTokenProvider provider = new JwtTokenProvider(
            new JwtProperties(Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()),
                    "crossborder-auth", Duration.ofMinutes(30)),
            Clock.systemUTC());
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(provider);

    private final AtomicReference<Authentication> authInChain = new AtomicReference<>();
    private final AtomicReference<Long> auditorInChain = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        AuditorContext.clear();
    }

    @Test
    void 유효한_토큰이면_principal과_수행자를_세팅하고_요청이_끝나면_수행자를_비운다() throws Exception {
        String token = provider.issue(new TokenPayload(5L, UserRole.BRAND_STAFF, 1L, 2L)).value();

        run("Bearer " + token);

        Authentication auth = authInChain.get();
        assertThat(auth.getPrincipal()).isEqualTo(new AuthenticatedUser(5L, UserRole.BRAND_STAFF, 1L, 2L));
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_BRAND_STAFF");
        assertThat(auditorInChain.get()).isEqualTo(5L);
        assertThat(AuditorContext.get()).isEmpty();
    }

    @Test
    void 토큰이_없으면_세팅없이_통과한다() throws Exception {
        run(null);

        assertThat(authInChain.get()).isNull();
        assertThat(auditorInChain.get()).isNull();
    }

    @Test
    void 변조된_토큰이면_세팅없이_통과한다() throws Exception {
        String token = provider.issue(new TokenPayload(5L, UserRole.ADMIN, null, null)).value();

        run("Bearer " + token.substring(0, token.length() - 2) + "xx");

        assertThat(authInChain.get()).isNull();
        assertThat(auditorInChain.get()).isNull();
    }

    @Test
    void Bearer_형식이_아니면_무시한다() throws Exception {
        String token = provider.issue(new TokenPayload(5L, UserRole.ADMIN, null, null)).value();

        run("Basic " + token);

        assertThat(authInChain.get()).isNull();
    }

    @Test
    void 체인에서_예외가_나도_수행자는_비워진다() {
        String token = provider.issue(new TokenPayload(5L, UserRole.ADMIN, null, null)).value();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        try {
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception ignored) {
        }

        assertThat(AuditorContext.get()).isEmpty();
    }

    private void run(String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            authInChain.set(SecurityContextHolder.getContext().getAuthentication());
            auditorInChain.set(AuditorContext.get().orElse(null));
        });
    }
}
