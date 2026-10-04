-- =====================================================
-- 로컬 전용 테스트 데이터 (로그인·권한·주문 시딩 테스트용)
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

-- =====================================================
-- 테스트 상품 세트 (주문 엑셀 시딩·통관 판정 테스트용)
--
-- 제품 3종 (테스트브랜드)
--  - TEST-TONER-001 토너 / TEST-CREAM-001 크림: 통관 분류 없음
--  - TEST-MASK-001 시트마스크 1매: SHEET_MASK (분류 합산 한도 판정 대상)
--
-- 판매상품 4종
--  - TEST-SP-TONER  토너 단품          (토너 x1)
--  - TEST-SP-CREAM  크림 단품          (크림 x1)
--  - TEST-SP-MASK10 시트마스크 10매    (마스크 x10)
--  - TEST-SP-SET    토너+크림 세트     (토너 x1, 크림 x1, 사은품 마스크 x1)
--
-- 채널 매핑 (옵션 없음은 '', 키는 채널+브랜드+코드+옵션, 기간 시작 없음 최초 매핑)
--  - RAKUTEN  RKT-TONER ''  / RKT-CREAM '' / RKT-MASK '10P' / RKT-SET ''
--  - QOO10    Q-TONER   ''  / Q-MASK '10P'
--
-- sale_products는 code 유니크 키가 없어 ON DUPLICATE로 멱등이 안 되므로 NOT EXISTS로 넣는다.
-- =====================================================

INSERT INTO products (brand_id, sku, name, name_eng, customs_category_id, hs_code, unit_price, currency, barcode,
                      origin, created_user_id)
SELECT b.id, v.sku, v.name, v.name_eng, cc.id, v.hs_code, v.unit_price, 'JPY', v.barcode, 'KR', 1
FROM (SELECT 'TEST-TONER-001' AS sku, '테스트 토너 200ml' AS name, 'Test Toner 200ml' AS name_eng,
             NULL AS category_code, '3304990000' AS hs_code, 2000.00 AS unit_price, '8800000000011' AS barcode
      UNION ALL
      SELECT 'TEST-CREAM-001', '테스트 크림 50ml', 'Test Cream 50ml', NULL, '3304990000', 3000.00, '8800000000028'
      UNION ALL
      SELECT 'TEST-MASK-001', '테스트 시트마스크 1매', 'Test Sheet Mask 1ea', 'SHEET_MASK', '3304990000', 200.00,
             '8800000000035') v
         JOIN brands b ON b.name = '테스트브랜드'
         JOIN companies c ON c.id = b.company_id AND c.business_no = '000-00-00001'
         LEFT JOIN customs_categories cc ON cc.code = v.category_code
ON DUPLICATE KEY UPDATE name                = VALUES(name),
                        name_eng            = VALUES(name_eng),
                        customs_category_id = VALUES(customs_category_id);

INSERT INTO sale_products (brand_id, name, code, created_user_id)
SELECT b.id, v.name, v.code, 1
FROM (SELECT 'TEST-SP-TONER' AS code, '테스트 토너 단품' AS name
      UNION ALL
      SELECT 'TEST-SP-CREAM', '테스트 크림 단품'
      UNION ALL
      SELECT 'TEST-SP-MASK10', '테스트 시트마스크 10매'
      UNION ALL
      SELECT 'TEST-SP-SET', '테스트 토너+크림 세트 (마스크 1매 증정)') v
         JOIN brands b ON b.name = '테스트브랜드'
         JOIN companies c ON c.id = b.company_id AND c.business_no = '000-00-00001'
WHERE NOT EXISTS (SELECT 1 FROM sale_products sp WHERE sp.code = v.code);

INSERT INTO sale_product_items (sale_product_id, product_id, quantity, is_gift, created_user_id)
SELECT sp.id, p.id, v.quantity, v.is_gift, 1
FROM (SELECT 'TEST-SP-TONER' AS sp_code, 'TEST-TONER-001' AS sku, 1 AS quantity, FALSE AS is_gift
      UNION ALL
      SELECT 'TEST-SP-CREAM', 'TEST-CREAM-001', 1, FALSE
      UNION ALL
      SELECT 'TEST-SP-MASK10', 'TEST-MASK-001', 10, FALSE
      UNION ALL
      SELECT 'TEST-SP-SET', 'TEST-TONER-001', 1, FALSE
      UNION ALL
      SELECT 'TEST-SP-SET', 'TEST-CREAM-001', 1, FALSE
      UNION ALL
      SELECT 'TEST-SP-SET', 'TEST-MASK-001', 1, TRUE) v
         JOIN sale_products sp ON sp.code = v.sp_code
         JOIN products p ON p.sku = v.sku
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity);

INSERT INTO sale_product_channel_mappings (sale_product_id, brand_id, channel_id, code, option_code, created_user_id)
SELECT sp.id, sp.brand_id, ch.id, v.code, v.option_code, 1
FROM (SELECT 'TEST-SP-TONER' AS sp_code, 'RAKUTEN' AS channel_code, 'RKT-TONER' AS code, '' AS option_code
      UNION ALL
      SELECT 'TEST-SP-CREAM', 'RAKUTEN', 'RKT-CREAM', ''
      UNION ALL
      SELECT 'TEST-SP-MASK10', 'RAKUTEN', 'RKT-MASK', '10P'
      UNION ALL
      SELECT 'TEST-SP-SET', 'RAKUTEN', 'RKT-SET', ''
      UNION ALL
      SELECT 'TEST-SP-TONER', 'QOO10', 'Q-TONER', ''
      UNION ALL
      SELECT 'TEST-SP-MASK10', 'QOO10', 'Q-MASK', '10P') v
         JOIN sale_products sp ON sp.code = v.sp_code
         JOIN sales_channels ch ON ch.code = v.channel_code
-- 매핑은 이력 테이블이라 키에 행이 하나라도 있으면 건드리지 않는다 (로컬에서 재지정한 이력 보존)
WHERE NOT EXISTS (SELECT 1
                  FROM sale_product_channel_mappings m
                  WHERE m.channel_id = ch.id
                    AND m.brand_id = sp.brand_id
                    AND m.code = v.code
                    AND m.option_code = v.option_code);
