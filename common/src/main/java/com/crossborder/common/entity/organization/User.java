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
        this.name = name;
    }

    public void changePassword(String encodedPassword) {
        requireNotSystem("비밀번호 변경");
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
     * 탈퇴 처리. 개인정보 마스킹 및 login_id 변형(유니크 반납)은 서비스/배치 책임 — 탈퇴 정책 참조
     */
    public void withdraw() {
        if (status == UserStatus.WITHDRAWN) {
            throw new IllegalStateException("이미 탈퇴한 사용자입니다. id=" + getId());
        }
        this.status = UserStatus.WITHDRAWN;
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isSystem() {
        return SYSTEM_LOGIN_ID.equals(loginId);
    }

    private void requireNotSystem(String action) {
        if (isSystem()) {
            throw new IllegalStateException("SYSTEM 계정은 " + action + "할 수 없습니다.");
        }
    }
}
