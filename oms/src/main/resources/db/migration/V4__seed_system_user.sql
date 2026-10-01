-- =====================================================
-- V4: SYSTEM 계정 시딩
--
-- 주문 수집·엑셀 시딩·배치 등 사람이 아닌 수행자의 created_user_id / changed_by 값.
-- 수행자가 지정되지 않은 저장도 이 계정으로 기록된다 (JpaAuditingConfig 대체값).
--
--  - id = 1 고정 (crossborder.audit.system-user-id 기본값과 일치해야 함)
--  - 로그인 불가: status INACTIVE + BCrypt 형식이 아닌 password('!')는 어떤 입력과도 매칭되지 않음
--  - users.chk_users_email_by_role 때문에 ADMIN + 이메일 형식으로 등록
-- =====================================================

INSERT INTO users (id, login_id, email, password, name, role, status)
VALUES (1, 'SYSTEM', 'system@crossborder.local', '!', 'SYSTEM', 'ADMIN', 'INACTIVE');
