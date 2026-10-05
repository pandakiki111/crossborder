package com.crossborder.oms.service.organization;

import com.crossborder.common.entity.organization.Brand;
import com.crossborder.common.entity.organization.Company;
import com.crossborder.common.entity.organization.User;
import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.oms.dto.organization.PasswordResetResponse;
import com.crossborder.oms.dto.organization.UserCreateRequest;
import com.crossborder.oms.dto.organization.UserResponse;
import com.crossborder.oms.dto.organization.UserUpdateRequest;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.ForbiddenException;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.oms.repository.CompanyRepository;
import com.crossborder.oms.repository.UserRepository;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.support.BrandWriteGuard;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 사용자 관리. 등록·변경은 ADMIN, 조회는 COMPANY_STAFF도 자사 사용자만 (SecurityConfig /api/users).
 * <ul>
 *   <li>등록: role별 정적 팩토리(소속 조합이 시그니처로 강제) + DB CHECK. 관리자군 login_id = 이메일. 비밀번호는 BCrypt만 저장.
 *       소속 회사·브랜드가 비활성이면 409</li>
 *   <li>수정: 이름·이메일(관리자군은 login_id 동반 변경, 응답 안내)·소속(COMPANY_STAFF 회사 / BRAND_STAFF 브랜드).
 *       role 변경은 미지원 — 재등록 (§8)</li>
 *   <li>탈퇴: 상태 전이 + 마스킹을 한 트랜잭션에서 즉시 (배치 유예 없음). login_id는 변형값으로 바꿔 원래 값을 반납한다</li>
 *   <li>비밀번호 재설정: 임시 비밀번호를 발급해 응답으로 한 번만 내린다 (저장은 해시)</li>
 *   <li>자기 자신은 비활성화·탈퇴할 수 없다 (마지막 관리자가 스스로 잠그는 것 방지). SYSTEM 계정은 엔티티 가드로 막힌다</li>
 * </ul>
 * 비활성·탈퇴 사용자의 기발급 JWT는 만료까지 유효하다 (토큰 폐기 저장소 없음 — 재로그인만 막는다).
 */
@Service
@Transactional
public class UserAdminService {

    /** 임시 비밀번호: 오독 문자(0·O·1·I·l) 뺀 영문 대소문자·숫자 12자 */
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int PASSWORD_LENGTH = 12;
    private static final DateTimeFormatter WITHDRAWN_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final BrandWriteGuard brandWriteGuard;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final RandomGenerator random = new SecureRandom();

    public UserAdminService(UserRepository userRepository, CompanyRepository companyRepository,
                            BrandWriteGuard brandWriteGuard, PasswordEncoder passwordEncoder, Clock clock) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.brandWriteGuard = brandWriteGuard;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * @throws InvalidRequestException role별 필수값 누락
     * @throws ConflictException       login_id 중복 / 소속 회사·브랜드 비활성
     */
    public UserResponse create(UserCreateRequest r) {
        String encoded = passwordEncoder.encode(r.password());
        String name = r.name().trim();
        User user = switch (r.role()) {
            case ADMIN -> User.createAdmin(requireEmail(r.email()), r.email(), encoded, name);
            case WORKER -> {
                if (!StringUtils.hasText(r.loginId())) {
                    throw new InvalidRequestException("WORKER는 작업자코드(loginId)가 필요합니다.");
                }
                yield User.createWorker(r.loginId().trim(), encoded, name);
            }
            case COMPANY_STAFF -> User.createCompanyStaff(requireEmail(r.email()), r.email(), encoded, name,
                    requireActiveCompany(r.companyId()).getId());
            case BRAND_STAFF -> {
                if (r.brandId() == null) {
                    throw new InvalidRequestException("BRAND_STAFF는 brandId가 필요합니다.");
                }
                yield User.createBrandStaff(requireEmail(r.email()), r.email(), encoded, name,
                        brandWriteGuard.requireWritable(r.brandId()));
            }
        };
        requireLoginIdAvailable(user.getLoginId());
        return UserResponse.from(userRepository.saveAndFlush(user));
    }

    /**
     * @throws ConflictException 탈퇴 사용자 / 바뀐 login_id 중복 / 새 소속 비활성
     */
    public UserResponse update(Long userId, UserUpdateRequest r) {
        User user = require(userId);
        if (StringUtils.hasText(r.name())) {
            user.changeName(r.name().trim());
        }
        boolean loginIdChanged = false;
        if (StringUtils.hasText(r.email()) && !r.email().equals(user.getEmail())) {
            if (user.getRole().isAdminGroup()) {
                requireLoginIdAvailable(r.email());
            }
            try {
                loginIdChanged = user.changeEmail(r.email());
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException(e.getMessage());
            }
        }
        if (r.companyId() != null) {
            requireRole(user, UserRole.COMPANY_STAFF, "companyId");
            user.changeCompany(requireActiveCompany(r.companyId()).getId());
        }
        if (r.brandId() != null) {
            requireRole(user, UserRole.BRAND_STAFF, "brandId");
            user.changeBrand(brandWriteGuard.requireWritable(r.brandId()));
        }
        userRepository.flush();
        return UserResponse.from(user, loginIdChanged ? UserResponse.LOGIN_ID_CHANGED : null);
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long userId, AuthenticatedUser viewer) {
        User user = require(userId);
        if (viewer.role() == UserRole.COMPANY_STAFF && !Objects.equals(user.getCompanyId(), viewer.companyId())) {
            throw new ForbiddenException("자사 사용자만 조회할 수 있습니다. userId=" + userId);
        }
        return UserResponse.from(user);
    }

    /** ADMIN: companyId 없으면 전체 / COMPANY_STAFF: 자사만 (companyId 파라미터 무시) */
    @Transactional(readOnly = true)
    public List<UserResponse> list(Long companyId, AuthenticatedUser viewer) {
        Long scope = viewer.role() == UserRole.COMPANY_STAFF ? viewer.companyId() : companyId;
        List<User> users = scope == null ? userRepository.findAllByOrderByIdAsc()
                : userRepository.findByCompanyIdOrderByIdAsc(scope);
        return users.stream().map(UserResponse::from).toList();
    }

    public UserResponse deactivate(Long userId, AuthenticatedUser actor) {
        User user = require(userId);
        requireNotSelf(user, actor, "비활성화");
        transition(user::deactivate);
        return UserResponse.from(user);
    }

    public UserResponse activate(Long userId) {
        User user = require(userId);
        transition(user::activate);
        return UserResponse.from(user);
    }

    /**
     * 탈퇴 + 마스킹 (즉시). login_id → WD{id}-{yyyyMMddHHmmss} (원래 값 반납), 관리자군 email → withdrawn-{id}@masked.invalid
     * (DB CHECK로 NULL 불가), WORKER email → NULL, 이름 → 탈퇴회원, 비밀번호 → 매칭 불가 값, OTP 삭제 (User.anonymize).
     */
    public UserResponse withdraw(Long userId, AuthenticatedUser actor) {
        User user = require(userId);
        requireNotSelf(user, actor, "탈퇴");
        transition(user::withdraw);
        String stamp = LocalDateTime.now(clock).format(WITHDRAWN_STAMP);
        user.anonymize("WD" + user.getId() + "-" + stamp, "withdrawn-" + user.getId() + "@masked.invalid");
        return UserResponse.from(user);
    }

    /**
     * @throws ConflictException 탈퇴 사용자·SYSTEM
     */
    public PasswordResetResponse resetPassword(Long userId) {
        User user = require(userId);
        String temporary = temporaryPassword();
        transition(() -> user.changePassword(passwordEncoder.encode(temporary)));
        return new PasswordResetResponse(user.getId(), temporary, PasswordResetResponse.NOTICE);
    }

    /** 엔티티 상태 가드(탈퇴·SYSTEM)를 409로 */
    private static void transition(Runnable change) {
        try {
            change.run();
        } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
        }
    }

    private String temporaryPassword() {
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            password.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }

    private void requireLoginIdAvailable(String loginId) {
        if (userRepository.existsByLoginId(loginId)) {
            throw new ConflictException("이미 사용 중인 로그인 아이디입니다: " + loginId);
        }
    }

    private Company requireActiveCompany(Long companyId) {
        if (companyId == null) {
            throw new InvalidRequestException("COMPANY_STAFF는 companyId가 필요합니다.");
        }
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("회사를 찾을 수 없습니다. companyId=" + companyId));
        if (!company.isActive()) {
            throw new ConflictException("비활성 회사에는 사용자를 등록·이동할 수 없습니다. companyId=" + companyId);
        }
        return company;
    }

    private static String requireEmail(String email) {
        if (!StringUtils.hasText(email)) {
            throw new InvalidRequestException("관리자군 사용자는 이메일이 필요합니다 (로그인 아이디로 쓰입니다).");
        }
        return email;
    }

    private static void requireRole(User user, UserRole role, String field) {
        if (user.getRole() != role) {
            throw new InvalidRequestException(field + "는 " + role + "만 바꿀 수 있습니다 (role 변경은 미지원 — 재등록). role="
                    + user.getRole());
        }
    }

    private static void requireNotSelf(User user, AuthenticatedUser actor, String action) {
        if (user.getId().equals(actor.userId())) {
            throw new ConflictException("자기 자신은 " + action + "할 수 없습니다.");
        }
    }

    private User require(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다. userId=" + userId));
    }
}
