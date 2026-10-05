-- =====================================================
-- V8: 주문 목록 1,000만 건 대비 인덱스 (README "대용량 조회 검증")
--
-- 근거: 1,007만 주문 실험 스키마(explain_lab) 실측, 버퍼풀 128MB(최악 조건).
-- 각 인덱스는 채택한 쿼리 형태(OrderQueryRepository)가 실제로 타는 것만 넣었다.
--
-- orders (ordered_at, id)                 ADMIN 목록: 기간 범위 + 최신순 정렬 + LIMIT을 인덱스 순서로 끝낸다.
--                                         지연 조인 1단계(ID 페이지), 상한 건수, 배송상태·SKU 프로브의 구동 범위도 이것.
--                                         365일 1페이지 14.2초 → 0.7ms
-- orders (company_id, ordered_at, id)     COMPANY_STAFF 스코프의 같은 경로. 365일 1페이지 3.9초 → 1.0ms
--                                         선두 컬럼이 company_id라 FK 인덱스 fk_orders_company를 대체한다
-- orders (channel_order_no)               주문번호 복수 정확 일치 (IN ≤ 500). uk_orders_channel_order는
--                                         (sales_channel_id, channel_order_no)라 채널 없이 못 탄다. 500개 5.9초 → 3.6ms
-- order_items (order_id, brand_id)        브랜드 필터·BRAND_STAFF 스코프의 목록 프로브
--                                         ((주문의 해당 브랜드 항목 수) > 0). 365일 1페이지 2.6초 → 22ms
--                                         선두가 order_id라 FK 인덱스 fk_order_items_order를 대체한다 (항목 수 서브쿼리도 이것)
-- order_items (sale_product_id, order_id) SKU 검색: 해석된 판매상품 ID의 항목 수 사전 판정과 희소 경로(IN 세미조인)를
--                                         인덱스만으로 처리. FK 인덱스 fk_order_items_sale_product를 대체한다
-- shipments (order_id, status)            배송상태 필터 프로브 ((주문의 해당 상태 회차 수) > 0, 미분리 = 유효 회차 0)를
--                                         인덱스만으로 판정. 365일 1페이지 30~130ms → 8ms, 건수 약 2배
--
-- FK 인덱스 대체: FK가 자동으로 만든 인덱스(fk_orders_company, fk_order_items_order, fk_order_items_sale_product)는
-- 같은 컬럼이 선두인 인덱스가 생기면 InnoDB가 스스로 지운다 (FK는 새 복합 인덱스를 쓴다). 그래서 DROP 문이 없다.
-- 온라인 DDL: ALGORITHM=INPLACE, LOCK=NONE — 운영 테이블 쓰기를 막지 않는다 (불가능하면 즉시 실패).
-- =====================================================

ALTER TABLE orders
    ADD INDEX idx_orders_ordered_at (ordered_at, id),
    ADD INDEX idx_orders_company_ordered (company_id, ordered_at, id),
    ADD INDEX idx_orders_channel_order_no (channel_order_no),
    ALGORITHM = INPLACE, LOCK = NONE;

ALTER TABLE order_items
    ADD INDEX idx_order_items_order_brand (order_id, brand_id),
    ADD INDEX idx_order_items_sale_product_order (sale_product_id, order_id),
    ALGORITHM = INPLACE, LOCK = NONE;

ALTER TABLE shipments
    ADD INDEX idx_shipments_order_status (order_id, status),
    ALGORITHM = INPLACE, LOCK = NONE;
