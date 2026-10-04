-- cbt 전용 스키마와 계정 (oms와 독립 시스템: 서로의 테이블을 직접 읽거나 쓰지 않는다)
-- cbt 계정은 crossborder_cbt에만 권한이 있어 경계를 DB 권한으로도 강제한다.
-- docker-entrypoint-initdb.d 스크립트는 볼륨이 비어 있는 최초 기동에만 실행된다.
CREATE DATABASE IF NOT EXISTS crossborder_cbt CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'cbt'@'%' IDENTIFIED BY 'cbtpass';
GRANT ALL PRIVILEGES ON crossborder_cbt.* TO 'cbt'@'%';
FLUSH PRIVILEGES;
