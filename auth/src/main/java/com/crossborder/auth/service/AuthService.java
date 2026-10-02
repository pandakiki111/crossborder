package com.crossborder.auth.service;

import com.crossborder.auth.dto.LoginRequest;
import com.crossborder.auth.dto.LoginResponse;
import com.crossborder.auth.exception.LoginFailedException;
import com.crossborder.auth.repository.LoginHistoryRepository;
import com.crossborder.auth.repository.UserRepository;
import com.crossborder.common.entity.auth.LoginHistory;
import com.crossborder.common.entity.organization.User;
import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.infra.jwt.IssuedToken;
import com.crossborder.infra.jwt.JwtTokenProvider;
import com.crossborder.infra.jwt.TokenPayload;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final LoginHistoryRepository loginHistoryRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    /** 사용자가 없을 때도 해시 비교를 수행해 응답 시간으로 계정 존재가 드러나지 않게 한다 */
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository, LoginHistoryRepository loginHistoryRepository,
                       PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.loginHistoryRepository = loginHistoryRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    /**
     * 실패 로그인은 기록하지 않는다 (login_histories는 성공 이력).
     *
     * @throws LoginFailedException 사용자 없음·비활성·비밀번호 불일치(401), WORKER(403)
     */
    @Transactional
    public LoginResponse login(LoginRequest request, String ipAddress) {
        User user = userRepository.findByLoginId(request.loginId()).orElse(null);

        // 사용자 없음·비활성(WITHDRAWN/INACTIVE 구분 없음)·비밀번호 불일치는 같은 응답.
        // 비활성 계정도 해시 비교를 거쳐 응답 시간 차이를 없앤다
        boolean passwordMatches = passwordEncoder.matches(request.password(),
                user != null ? user.getPassword() : dummyPasswordHash);
        if (user == null || !user.isActive() || !passwordMatches) {
            throw LoginFailedException.badCredentials();
        }

        // 비밀번호 검증 뒤에 판정해야 작업자코드 존재 여부가 비밀번호 없이 드러나지 않는다
        if (user.getRole() == UserRole.WORKER) {
            // TODO: 기기 인증(2차) 구현 시 이 차단을 기기 검증 체인으로 교체 — docs/device-registration.md 참조
            throw LoginFailedException.workerDeviceRequired();
        }

        IssuedToken token = jwtTokenProvider.issue(
                new TokenPayload(user.getId(), user.getRole(), user.getCompanyId(), user.getBrandId()));
        // WORKER는 위에서 막혔으므로 관리자군 팩토리만 쓴다
        loginHistoryRepository.save(LoginHistory.ofAdminGroup(user.getId(), ipAddress));

        return new LoginResponse(token.value(), token.expiresAt(), user.getName(), user.getRole());
    }
}
