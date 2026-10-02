package com.crossborder.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.crossborder.auth.dto.LoginRequest;
import com.crossborder.auth.dto.LoginResponse;
import com.crossborder.auth.exception.LoginFailedException;
import com.crossborder.auth.repository.LoginHistoryRepository;
import com.crossborder.auth.repository.UserRepository;
import com.crossborder.common.entity.auth.LoginHistory;
import com.crossborder.common.entity.organization.User;
import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.infra.jwt.JwtProperties;
import com.crossborder.infra.jwt.JwtTokenProvider;
import com.crossborder.infra.jwt.TokenPayload;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceTest {

    private static final String PASSWORD = "Test1234!";
    private static final String IP = "127.0.0.1";
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    // 테스트 속도를 위해 최소 strength
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(
            new JwtProperties(Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()),
                    "crossborder-auth", Duration.ofMinutes(30)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final UserRepository userRepository = mock(UserRepository.class);
    private final LoginHistoryRepository loginHistoryRepository = mock(LoginHistoryRepository.class);

    private final AuthService authService =
            new AuthService(userRepository, loginHistoryRepository, passwordEncoder, jwtTokenProvider);

    @Test
    void 로그인_성공시_토큰과_사용자정보를_돌려주고_이력을_남긴다() {
        User admin = saved(User.createCompanyStaff("staff@test.local", "staff@test.local", encoded(), "직원", 7L), 10L);
        given(userRepository.findByLoginId("staff@test.local")).willReturn(Optional.of(admin));

        LoginResponse response = authService.login(new LoginRequest("staff@test.local", PASSWORD), IP);

        assertThat(response.name()).isEqualTo("직원");
        assertThat(response.role()).isEqualTo(UserRole.COMPANY_STAFF);
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(jwtTokenProvider.verify(response.accessToken()))
                .isEqualTo(new TokenPayload(10L, UserRole.COMPANY_STAFF, 7L, null));

        ArgumentCaptor<LoginHistory> history = ArgumentCaptor.forClass(LoginHistory.class);
        verify(loginHistoryRepository).save(history.capture());
        assertThat(history.getValue().getUserId()).isEqualTo(10L);
        assertThat(history.getValue().getDeviceId()).isNull();
        assertThat(history.getValue().getIpAddress()).isEqualTo(IP);
    }

    @Test
    void 없는_사용자는_공통_인증실패() {
        given(userRepository.findByLoginId("nobody")).willReturn(Optional.empty());

        assertBadCredentials(new LoginRequest("nobody", PASSWORD));
    }

    @Test
    void 비밀번호가_틀리면_공통_인증실패() {
        User admin = saved(User.createAdmin("admin@test.local", "admin@test.local", encoded(), "관리자"), 1L);
        given(userRepository.findByLoginId("admin@test.local")).willReturn(Optional.of(admin));

        assertBadCredentials(new LoginRequest("admin@test.local", "wrong"));
    }

    @Test
    void 비활성_사용자는_비밀번호가_맞아도_공통_인증실패() {
        User inactive = saved(User.createAdmin("admin@test.local", "admin@test.local", encoded(), "관리자"), 1L);
        inactive.deactivate();
        User withdrawn = saved(User.createAdmin("gone@test.local", "gone@test.local", encoded(), "탈퇴"), 2L);
        withdrawn.withdraw();
        given(userRepository.findByLoginId("admin@test.local")).willReturn(Optional.of(inactive));
        given(userRepository.findByLoginId("gone@test.local")).willReturn(Optional.of(withdrawn));

        assertBadCredentials(new LoginRequest("admin@test.local", PASSWORD));
        assertBadCredentials(new LoginRequest("gone@test.local", PASSWORD));
    }

    @Test
    void 작업자는_비밀번호가_맞으면_기기_안내로_거부된다() {
        User worker = saved(User.createWorker("W0001", encoded(), "작업자"), 3L);
        given(userRepository.findByLoginId("W0001")).willReturn(Optional.of(worker));

        assertThatThrownBy(() -> authService.login(new LoginRequest("W0001", PASSWORD), IP))
                .isInstanceOfSatisfying(LoginFailedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getMessage()).isEqualTo("작업자 계정은 등록된 기기에서만 로그인할 수 있습니다");
                });
        verify(loginHistoryRepository, never()).save(any());
    }

    @Test
    void 작업자도_비밀번호가_틀리면_공통_인증실패_계정존재를_노출하지_않는다() {
        User worker = saved(User.createWorker("W0001", encoded(), "작업자"), 3L);
        given(userRepository.findByLoginId("W0001")).willReturn(Optional.of(worker));

        assertBadCredentials(new LoginRequest("W0001", "wrong"));
    }

    private void assertBadCredentials(LoginRequest request) {
        assertThatThrownBy(() -> authService.login(request, IP))
                .isInstanceOfSatisfying(LoginFailedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getMessage()).isEqualTo("아이디 또는 비밀번호가 올바르지 않습니다");
                });
        verify(loginHistoryRepository, never()).save(any());
    }

    private String encoded() {
        return passwordEncoder.encode(PASSWORD);
    }

    private static User saved(User user, Long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
