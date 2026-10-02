-- =====================================================
-- 로컬 전용 테스트 데이터 (로그인·권한 테스트용)
--
-- local 프로필에서만 실행된다 (application-local.yml의 spring.flyway.locations).
-- 운영 DB에는 들어가지 않는다.
--
-- Repeatable 마이그레이션: 파일 내용이 바뀌면 다시 실행되므로 모든 INSERT는 멱등으로 작성한다.
-- (ON DUPLICATE KEY UPDATE — INSERT IGNORE는 CHECK 위반까지 경고로 삼켜서 쓰지 않는다)
--  - 멱등성은 유니크 키 존재에 의존한다 (companies: business_no / brands: company_id+name / users: login_id).
--    유니크 키가 NULL이면 중복 판정이 안 되므로 테스트 데이터에는 반드시 값을 넣는다.
--  - 유니크 키가 없는 append 테이블(stock_movements, order_status_history 등)은 ON DUPLICATE로
--    멱등이 안 된다. 시딩 추가 시 NOT EXISTS 조건 등 별도 방식 필요.
--  - V2 이후 테이블(products 등)은 created_user_id NOT NULL → SYSTEM(id = 1)을 넣는다.
--
-- 회사 1 / 브랜드 1 / 역할별 사용자 4명
--  - 비밀번호는 모두 'Test1234!' (BCryptPasswordEncoder 해시)
--  - 관리자군 login_id는 이메일 값, WORKER는 작업자코드 (V1 users 규칙)
--  - 관리자군 otp_enabled = FALSE: 최초 로그인 시 OTP 등록 흐름 대상
-- =====================================================

INSERT INTO companies (name, business_no)
VALUES ('테스트상사', '000-00-00001')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO brands (company_id, name)
VALUES ((SELECT id FROM companies WHERE business_no = '000-00-00001'), '테스트브랜드')
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- ADMIN / WORKER: 소속 없음
INSERT INTO users (login_id, email, password, name, role)
VALUES ('admin@test.local', 'admin@test.local',
        '$2a$10$QDk6l/c.0XEYjXSVP/hBKebowQ3uIhRDR8S/Xb7W14bFC3kD5GfoG', '테스트관리자', 'ADMIN'),
       ('W0001', NULL,
        '$2a$10$QDk6l/c.0XEYjXSVP/hBKebowQ3uIhRDR8S/Xb7W14bFC3kD5GfoG', '테스트작업자', 'WORKER')
ON DUPLICATE KEY UPDATE password = VALUES(password), name = VALUES(name);

-- COMPANY_STAFF: company만
INSERT INTO users (login_id, email, password, name, role, company_id)
VALUES ('company@test.local', 'company@test.local',
        '$2a$10$QDk6l/c.0XEYjXSVP/hBKebowQ3uIhRDR8S/Xb7W14bFC3kD5GfoG', '테스트회사직원', 'COMPANY_STAFF',
        (SELECT id FROM companies WHERE business_no = '000-00-00001'))
ON DUPLICATE KEY UPDATE password = VALUES(password), name = VALUES(name);

-- BRAND_STAFF: company + brand
INSERT INTO users (login_id, email, password, name, role, company_id, brand_id)
SELECT 'brand@test.local', 'brand@test.local',
       '$2a$10$QDk6l/c.0XEYjXSVP/hBKebowQ3uIhRDR8S/Xb7W14bFC3kD5GfoG', '테스트브랜드직원', 'BRAND_STAFF',
       b.company_id, b.id
FROM brands b
         JOIN companies c ON c.id = b.company_id
WHERE c.business_no = '000-00-00001'
  AND b.name = '테스트브랜드'
ON DUPLICATE KEY UPDATE password = VALUES(password), name = VALUES(name);
