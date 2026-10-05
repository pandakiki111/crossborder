package com.crossborder.oms.config;

import com.crossborder.infra.jwt.JwtTokenProvider;
import com.crossborder.oms.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * oms는 auth가 발급한 액세스 토큰만 검증한다 (세션·로그인 폼 없음).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenProvider jwtTokenProvider)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // /error: 오류 디스패치에는 JWT 필터가 다시 돌지 않으므로, 허용하지 않으면 403·404까지 401로 덮인다
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 사용자: 조회는 COMPANY_STAFF도(자사만 — UserAdminService), 등록·변경은 ADMIN
                        .requestMatchers(HttpMethod.GET, "/api/users/**").hasAnyRole("ADMIN", "COMPANY_STAFF")
                        .requestMatchers("/api/users/**").hasRole("ADMIN")
                        // 제품: 조회는 BRAND_STAFF도(자기 브랜드 — 판매상품 구성용), 등록·변경은 ADMIN·COMPANY_STAFF(자사 브랜드)
                        .requestMatchers(HttpMethod.GET, "/api/products/**").hasAnyRole("ADMIN", "COMPANY_STAFF", "BRAND_STAFF")
                        .requestMatchers("/api/products/**").hasAnyRole("ADMIN", "COMPANY_STAFF")
                        // WORKER는 cbt 전용 사용자 → oms 전 API 차단 (403). 소속 스코프는 서비스의 ScopePolicy가 검사
                        .anyRequest().hasAnyRole("ADMIN", "COMPANY_STAFF", "BRAND_STAFF"))
                // httpBasic/formLogin을 끄면 기본 진입점이 403이 되므로 미인증은 401로 명시
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
