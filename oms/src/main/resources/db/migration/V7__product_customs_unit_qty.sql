-- =====================================================
-- V7: 제품 통관 환산 계수
--
-- products.customs_unit_qty  재고 1단위당 통관 환산 수량 (예: 시트마스크 10매입 박스 = 10).
-- 분류 한도 판정: 분류별 Σ(전개 수량 × customs_unit_qty) > customs_categories.qty_limit 이면 회차 분할.
-- 기존 제품은 1 (낱개 = 1단위).
-- =====================================================

ALTER TABLE products
    ADD COLUMN customs_unit_qty INT NOT NULL DEFAULT 1 COMMENT '재고 1단위당 통관 환산 수량 (10매입 박스 = 10)' AFTER customs_category_id,
    ADD CONSTRAINT chk_products_customs_unit_qty CHECK (customs_unit_qty >= 1);
