-- =====================================================
-- V1: 조직/사용자/인증 도메인
--
-- companies          입점 회사
-- brands             입점사 소속 브랜드 (회사 1:N 브랜드)
-- users              통합 사용자 (role 기반 단일 테이블)
--                    - ADMIN / WORKER: 소속 없음 (통합관리자, 물류단기작업자)
--                    - COMPANY_STAFF: company 소속
--                    - BRAND_STAFF: company + brand 소속
--                    - 로그인 식별자는 login_id
--                      (관리자군: 이메일 값, 작업자: 사번/작업자코드)
--                    - 관리자군은 TOTP(구글 OTP) 사용, 작업자는 기기 신뢰 기반
-- registered_devices 작업자 로그인 허용 기기 (등록 코드 방식으로 등록)
-- login_histories    로그인 감사 이력 (작업자 로그인 위치를 기록하여 창고 이외의 장소에서 로그인한 이력이 있는지 확인하는 용도)
-- =====================================================

CREATE TABLE companies
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    business_no VARCHAR(20)  NULL COMMENT '사업자등록번호',
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / INACTIVE',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_companies_business_no UNIQUE (business_no)
) COMMENT '입점 회사';

CREATE TABLE brands
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT       NOT NULL COMMENT '소속 회사',
    name       VARCHAR(100) NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / INACTIVE',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_brands_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT uk_brands_company_name UNIQUE (company_id, name)
) COMMENT '입점사 브랜드 (브랜드명 유니크는 회사 단위)';

CREATE TABLE users
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    login_id    VARCHAR(50)  NOT NULL COMMENT '로그인 식별자 (관리자군: 이메일 / 작업자: 사번·작업자코드)',
    email       VARCHAR(100) NULL COMMENT '관리자군 필수, WORKER는 NULL 허용',
    password    VARCHAR(255) NOT NULL COMMENT 'BCrypt 해시',
    name        VARCHAR(50)  NOT NULL,
    role        VARCHAR(20)  NOT NULL COMMENT 'ADMIN / WORKER / COMPANY_STAFF / BRAND_STAFF',
    company_id  BIGINT       NULL COMMENT 'COMPANY_STAFF, BRAND_STAFF만 값 존재',
    brand_id    BIGINT       NULL COMMENT 'BRAND_STAFF만 값 존재',
    otp_secret  VARCHAR(100) NULL COMMENT 'TOTP 시크릿 (관리자군만)',
    otp_enabled BOOLEAN      NOT NULL DEFAULT FALSE COMMENT 'TOTP 등록 완료 여부',
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / INACTIVE',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_users_login_id UNIQUE (login_id),
    CONSTRAINT fk_users_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_users_brand FOREIGN KEY (brand_id) REFERENCES brands (id),
    CONSTRAINT chk_users_role_scope CHECK (
        (role = 'ADMIN'         AND company_id IS NULL     AND brand_id IS NULL) OR
        (role = 'WORKER'        AND company_id IS NULL     AND brand_id IS NULL) OR
        (role = 'COMPANY_STAFF' AND company_id IS NOT NULL AND brand_id IS NULL) OR
        (role = 'BRAND_STAFF'   AND company_id IS NOT NULL AND brand_id IS NOT NULL)
        ),
    CONSTRAINT chk_users_email_by_role CHECK (
        role = 'WORKER' OR email IS NOT NULL
        )
) COMMENT '통합 사용자 (role 기반, 인증 방식은 역할군별 분리)';

CREATE TABLE registered_devices
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_name   VARCHAR(100) NOT NULL COMMENT '기기 표시명 (예: 1층 피킹존 PC-03)',
    device_token  VARCHAR(255) NOT NULL COMMENT '기기 식별 토큰 해시 (원문은 해당 PC 쿠키에만 존재)',
    location      VARCHAR(100) NULL COMMENT '설치 위치 (예: 본창고 1층 피킹존)',
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / BLOCKED',
    registered_by BIGINT       NOT NULL COMMENT '등록 코드를 발급한 관리자',
    last_used_at  DATETIME     NULL COMMENT '마지막 로그인 사용 시각 (유령 기기 식별용)',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_devices_token UNIQUE (device_token),
    CONSTRAINT fk_devices_registered_by FOREIGN KEY (registered_by) REFERENCES users (id)
) COMMENT '작업자(WORKER) 로그인 허용 기기 (등록 코드는 Redis 임시 보관)';

CREATE TABLE login_histories
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    device_id    BIGINT      NULL COMMENT 'WORKER 로그인 시 사용 기기, 관리자군은 NULL',
    ip_address   VARCHAR(45) NULL COMMENT 'IPv6 최대 길이 대비',
    logged_in_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_login_histories_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_login_histories_device FOREIGN KEY (device_id) REFERENCES registered_devices (id)
) COMMENT '로그인 감사 이력 (append-only)';