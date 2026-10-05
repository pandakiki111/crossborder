package com.crossborder.common.entity.organization;

import com.crossborder.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 통합 사용자 (role 기반, 인증 방식은 역할군별 분리)
 * <p>
 * role별 소속 규칙(chk_users_role_scope)은 역할별 생성 메서드로 보장한다.
 */
@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    /** 시스템 수행자 계정 (V4 시딩). 로그인 불가 상태를 유지해야 한다. */
    public static final String SYSTEM_LOGIN_ID = "SYSTEM";

    /** 탈퇴 시 비밀번호 대체값 — BCrypt 형식이 아니라 어떤 입력과도 매칭되지 않는다 (SYSTEM 계정과 같은 값) */
    public static final String UNUSABLE_PASSWORD = "!";
    /** 탈퇴 시 이름 대체값 */
    public static final String WITHDRAWN_NAME = "탈퇴회원";

    @Column(name = "login_id", nullable = false, length = 50)
    private String loginId;

    @Column(length = 100)
    private String email;

    @Column(nullable = false, length = 255)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "brand_id")
    private Long brandId;

    @Column(name = "otp_secret", length = 100)
    private String otpSecret;

    @Column(name = "otp_enabled", nullable = false)
    private boolean otpEnabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private User(String loginId, String email, String password, String name,
                 UserRole role, Long companyId, Long brandId) {
        if (role.isAdminGroup() && email == null) {
            throw new IllegalArgumentException("관리자군 사용자는 이메일이 필수입니다.");
        }
        this.loginId = loginId;
        this.email = email;
        this.password = password;
        this.name = name;
        this.role = role;
        this.companyId = companyId;
        this.brandId = brandId;
        this.otpEnabled = false;
        this.status = UserStatus.ACTIVE;
    }

    public static User createAdmin(String loginId, String email, String encodedPassword, String name) {
        return new User(loginId, email, encodedPassword, name, UserRole.ADMIN, null, null);
    }

    public static User createWorker(String loginId, String encodedPassword, String name) {
        return new User(loginId, null, encodedPassword, name, UserRole.WORKER, null, null);
    }

    public static User createCompanyStaff(String loginId, String email, String encodedPassword, String name,
                                          Long companyId) {
        if (companyId == null) {
            throw new IllegalArgumentException("COMPANY_STAFF는 소속 회사가 필수입니다.");
        }
        return new User(loginId, email, encodedPassword, name, UserRole.COMPANY_STAFF, companyId, null);
    }

    /**
     * 회사-브랜드 소속 불일치를 막기 위해 companyId는 저장된 Brand에서 꺼낸다.
     */
    public static User createBrandStaff(String loginId, String email, String encodedPassword, String name,
                                        Brand brand) {
        if (brand == null) {
            throw new IllegalArgumentException("BRAND_STAFF는 소속 브랜드가 필수입니다.");
        }
        Objects.requireNonNull(brand.getId(), "저장되지 않은 브랜드로 사용자를 만들 수 없습니다.");
        return new User(loginId, email, encodedPassword, name, UserRole.BRAND_STAFF,
                brand.getCompanyId(), brand.getId());
    }

    public void changeName(String name) {
        requireNotWithdrawn();
        this.name = name;
    }

    /**
     * 이메일 변경. 관리자군은 login_id = 이메일 값이라 login_id도 함께 바뀐다 (반환값 true — 호출 측이 사용자에게 안내).
     * WORKER는 login_id(작업자코드)와 무관하다.
     * <p>
     * login_id가 바뀌어도 기발급 JWT는 만료까지 유효하다 — claims가 userId 기준이라 동작에 문제가 없고, 의도된 동작이다.
     *
     * @return login_id가 바뀌었으면 true
     */
    public boolean changeEmail(String email) {
        requireNotWithdrawn();
        requireNotSystem("이메일 변경");
        if (role.isAdminGroup() && (email == null || email.isBlank())) {
            throw new IllegalArgumentException("관리자군 사용자는 이메일이 필수입니다.");
        }
        this.email = email;
        if (role.isAdminGroup() && !email.equals(loginId)) {
            this.loginId = email;
            return true;
        }
        return false;
    }

    /** COMPANY_STAFF 소속 회사 변경 (role 변경은 미지원 — 재등록) */
    public void changeCompany(Long companyId) {
        requireNotWithdrawn();
        if (role != UserRole.COMPANY_STAFF) {
            throw new IllegalStateException("소속 회사 변경은 COMPANY_STAFF만 가능합니다. role=" + role);
        }
        this.companyId = Objects.requireNonNull(companyId, "COMPANY_STAFF는 소속 회사가 필수입니다.");
    }

    /** BRAND_STAFF 소속 브랜드 변경. 회사는 브랜드에서 꺼낸다 (회사-브랜드 소속 불일치 방지, createBrandStaff와 같다) */
    public void changeBrand(Brand brand) {
        requireNotWithdrawn();
        if (role != UserRole.BRAND_STAFF) {
            throw new IllegalStateException("소속 브랜드 변경은 BRAND_STAFF만 가능합니다. role=" + role);
        }
        Objects.requireNonNull(brand.getId(), "저장되지 않은 브랜드로 소속을 바꿀 수 없습니다.");
        this.brandId = brand.getId();
        this.companyId = brand.getCompanyId();
    }

    public void changePassword(String encodedPassword) {
        requireNotSystem("비밀번호 변경");
        requireNotWithdrawn();
        this.password = encodedPassword;
    }

    /**
     * OTP 시크릿 발급. 사용자가 인증 코드를 확인하기 전까지는 비활성 상태.
     */
    public void registerOtpSecret(String otpSecret) {
        if (!role.isAdminGroup()) {
            throw new IllegalStateException("WORKER는 OTP를 사용하지 않습니다.");
        }
        this.otpSecret = otpSecret;
        this.otpEnabled = false;
    }

    public void enableOtp() {
        if (otpSecret == null) {
            throw new IllegalStateException("OTP 시크릿이 발급되지 않았습니다.");
        }
        this.otpEnabled = true;
    }

    public void resetOtp() {
        this.otpSecret = null;
        this.otpEnabled = false;
    }

    public void activate() {
        requireNotSystem("활성화");
        if (status == UserStatus.WITHDRAWN) {
            throw new IllegalStateException("탈퇴한 사용자는 활성화할 수 없습니다.");
        }
        this.status = UserStatus.ACTIVE;
    }

    public void deactivate() {
        if (status == UserStatus.WITHDRAWN) {
            throw new IllegalStateException("탈퇴한 사용자입니다.");
        }
        this.status = UserStatus.INACTIVE;
    }

    /**
     * 탈퇴 처리 (상태만). 개인정보 마스킹·login_id 변형(유니크 반납)은 {@link #anonymize}로 같은 트랜잭션에서 한다
     * (UserAdminService.withdraw — 즉시 처리, 배치 유예 없음).
     */
    public void withdraw() {
        requireNotSystem("탈퇴");
        if (status == UserStatus.WITHDRAWN) {
            throw new IllegalStateException("이미 탈퇴한 사용자입니다. id=" + getId());
        }
        this.status = UserStatus.WITHDRAWN;
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 탈퇴 마스킹 체크리스트 (탈퇴 상태에서만):
     * <ul>
     *   <li>login_id → 변형값 (원래 값은 유니크에서 반납 — 같은 아이디로 재가입 가능)</li>
     *   <li>email → 관리자군은 마스킹 주소(DB CHECK로 NULL 불가), WORKER는 NULL</li>
     *   <li>name → "탈퇴회원", password → 매칭 불가 값, OTP 시크릿 삭제</li>
     * </ul>
     * 변형값 형식은 서비스가 정한다 (UserAdminService).
     */
    public void anonymize(String maskedLoginId, String maskedEmail) {
        if (status != UserStatus.WITHDRAWN) {
            throw new IllegalStateException("탈퇴한 사용자만 마스킹합니다. id=" + getId());
        }
        this.loginId = maskedLoginId;
        this.email = role.isAdminGroup() ? maskedEmail : null;
        this.name = WITHDRAWN_NAME;
        this.password = UNUSABLE_PASSWORD;
        resetOtp();
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isSystem() {
        return SYSTEM_LOGIN_ID.equals(loginId);
    }

    private void requireNotWithdrawn() {
        if (status == UserStatus.WITHDRAWN) {
            throw new IllegalStateException("탈퇴한 사용자는 변경할 수 없습니다. id=" + getId());
        }
    }

    private void requireNotSystem(String action) {
        if (isSystem()) {
            throw new IllegalStateException("SYSTEM 계정은 " + action + "할 수 없습니다.");
        }
    }
}
