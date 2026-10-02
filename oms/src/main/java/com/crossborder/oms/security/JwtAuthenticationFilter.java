package com.crossborder.oms.security;

import com.crossborder.infra.jpa.AuditorContext;
import com.crossborder.infra.jwt.InvalidTokenException;
import com.crossborder.infra.jwt.JwtTokenProvider;
import com.crossborder.infra.jwt.TokenPayload;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer 토큰이 있으면 검증해서 SecurityContext와 AuditorContext를 채운다.
 * <p>
 * 토큰이 없거나 유효하지 않으면 아무것도 세팅하지 않고 통과시킨다.
 * 미인증 요청의 거부(401)는 SecurityConfig의 authorizeHttpRequests 규칙이 담당한다.
 * <p>
 * 서블릿 필터로 자동 등록되지 않도록 빈이 아니라 SecurityConfig에서 직접 생성한다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String token = resolveToken(request);
            if (token != null) {
                authenticate(token);
            }
            chain.doFilter(request, response);
        } finally {
            // 스레드풀 재사용 시 이전 요청의 수행자가 다음 요청에 섞이지 않도록 반드시 비운다
            AuditorContext.clear();
        }
    }

    private void authenticate(String token) {
        TokenPayload payload;
        try {
            payload = jwtTokenProvider.verify(token);
        } catch (InvalidTokenException e) {
            log.debug("JWT 검증 실패: reason={}", e.getReason());
            return;
        }

        AuthenticatedUser user = AuthenticatedUser.from(payload);
        // "ROLE_" 접두사: hasRole("ADMIN")이 ROLE_ADMIN 권한과 매칭된다
        var authentication = new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        AuditorContext.set(user.userId());
    }

    private static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
