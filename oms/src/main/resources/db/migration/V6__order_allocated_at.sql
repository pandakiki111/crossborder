-- =====================================================
-- V6: 주문 재고 할당 완료 시각
--
-- orders.allocated_at  주문의 재고 할당(allocated_stock 반영) 완료 시각. NULL이면 미할당.
--
-- 쓰임
--  - 멱등: 할당은 allocated_at IS NULL인 주문에만 하고 같은 트랜잭션에서 시각을 기록한다
--    (소급 배치를 여러 번 돌려도 이중 할당되지 않는다)
--  - 정합 검증: allocated_at이 있는 주문의 유효 항목 전개 합 = products.allocated_stock 이어야 한다
--
-- 규칙 (서비스 레이어 보장):
--  - 등록 경로(시딩·수집)는 주문 INSERT와 할당을 한 트랜잭션으로 묶는다 → 등록됐는데 미할당인 주문을 남기지 않는다
--  - 매핑안됨(mapping_pending) 주문은 전개할 수 없어 할당하지 않고, 매핑 완료 시점에 주문 전체를 할당한다
--  - 이 컬럼 이전에 등록된 주문은 NULL로 남으며 관리자 소급 API로 할당한다
-- =====================================================

ALTER TABLE orders
    ADD COLUMN allocated_at DATETIME NULL COMMENT '재고 할당 완료 시각 (NULL이면 미할당)' AFTER mapping_pending;
