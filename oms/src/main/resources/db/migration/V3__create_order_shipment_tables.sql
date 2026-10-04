-- =====================================================
-- V3: 주문/출고 도메인
--
-- orders                주문 (마켓 1주문 = 1행 불변, 분리해도 쪼개지 않음)
-- order_items           주문 항목 (이형 참조: 판매상품 / 건별 사은품, 브랜드 스코프. 매핑안됨 항목은 채널 수신 코드 보존)
-- shipments             출고 회차 (분리·출고지시의 단위, CREATED는 지시 전 대기)
-- shipment_items        회차별 출고 항목 (어느 주문 항목 몇 개가 이 회차인지)
-- order_status_history  주문/출고 상태 변경 통합 이력 (append-only)
--
-- 핵심 규칙 (서비스 레이어 보장):
--  - 분리/출고지시 대상은 status=ORDERED 항목만
--  - 한 shipment는 단일 브랜드 항목만 포함
--  - 주문 SHIPPING 전이는 첫 회차 INSTRUCTED 시점
--  - 취소 가능 여부는 항목 단위: INSTRUCTED 이후 회차에 물린 항목은 취소 불가
--    (SHIPPING 주문이어도 CREATED 회차 항목은 취소 가능)
--  - 주문 전체 취소는 모든 회차가 CREATED/CANCELED일 때만 (= PAID / PARTIAL_CANCELED)
--  - 사은품(GIFT_PRODUCT) 항목은 수량 부분취소 없이 전체 취소만
--  - 취소 시 CREATED 회차에서 항목 제거, 빈 회차는 CANCELED 처리
--
-- 매핑안됨 (판매상품 미확정 항목)
--  - 채널 상품코드에 (그 브랜드의) 매핑이 없어도 주문은 받는다. 해당 항목은 sale_product_id 없이
--    channel_product_code/option_code(채널 수신값)로 저장하고 주문은 mapping_pending = TRUE.
--  - mapping_pending은 status와 독립: 매핑안됨이어도 결제완료·부분취소는 status에 그대로 반영되어
--    PAID·PARTIAL_CANCELED 필터에 걸린다.
--  - 매핑 등록 후 ordered_at 구간 매핑으로 항목을 확정하고, 남은 유효 매핑안됨 항목이 없으면 FALSE로 내린다.
--  - 브랜드는 수집 시점에 안다(엑셀 시딩은 업로드 시 지정, 수집은 스토어 계정 기준) → 매핑안됨 항목도 brand_id 보유.
--  - mapping_pending 주문은 분리·출고지시 대상 아님
-- =====================================================

CREATE TABLE orders
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no          VARCHAR(50)   NOT NULL COMMENT '내부 주문번호',
    sales_channel_id  BIGINT        NOT NULL COMMENT '판매채널',
    channel_order_no  VARCHAR(100)  NOT NULL COMMENT '마켓측 주문번호',
    company_id        BIGINT        NOT NULL COMMENT '조회 스코프용 (멀티브랜드여도 회사는 단일)',
    status            VARCHAR(20)   NOT NULL DEFAULT 'PAID' COMMENT 'PAID / PARTIAL_CANCELED / CANCELED / SHIPPING / DELIVERED',
    mapping_pending   BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '매핑안됨 유효 항목 있음 (분리·출고 불가)',
    total_item_amount DECIMAL(12,2) NULL COMMENT '상품금액 합계 (마켓 수신값)',
    paid_amount       DECIMAL(12,2) NULL COMMENT '실결제금액 (마켓 수신값, 할인·포인트 반영)',
    currency          CHAR(3)       NULL COMMENT 'ISO 통화 (JPY)',
    orderer_name      VARCHAR(100)  NOT NULL COMMENT '주문자명',
    receiver_name     VARCHAR(100)  NOT NULL COMMENT '수취인명',
    receiver_phone    VARCHAR(30)   NULL COMMENT '수취인 연락처',
    receiver_zipcode  VARCHAR(10)   NULL COMMENT '수취인 우편번호',
    receiver_address  VARCHAR(500)  NOT NULL COMMENT '수취인 주소',
    delivery_memo     VARCHAR(300)  NULL COMMENT '배송 메모',
    market_memo       VARCHAR(300)  NULL COMMENT '마켓 메모',
    ordered_at        DATETIME      NOT NULL COMMENT '마켓 주문 시각',
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_user_id   BIGINT        NOT NULL COMMENT '등록자 (수집·시딩은 SYSTEM 계정)',
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_user_id   BIGINT        NULL COMMENT '마지막 수정자',
    CONSTRAINT uk_orders_order_no UNIQUE (order_no),
    CONSTRAINT uk_orders_channel_order UNIQUE (sales_channel_id, channel_order_no),
    CONSTRAINT fk_orders_sales_channel FOREIGN KEY (sales_channel_id) REFERENCES sales_channels (id),
    CONSTRAINT fk_orders_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_orders_created_user FOREIGN KEY (created_user_id) REFERENCES users (id),
    CONSTRAINT fk_orders_updated_user FOREIGN KEY (updated_user_id) REFERENCES users (id),
    INDEX idx_orders_mapping_pending (mapping_pending, ordered_at)
) COMMENT '주문 (uk_orders_channel_order가 수집·엑셀 시딩 멱등성 키)';

CREATE TABLE order_items
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT        NOT NULL COMMENT '소속 주문',
    brand_id        BIGINT        NOT NULL COMMENT '항목 브랜드 (참조 대상에서 주문 시점 복사)',
    item_type       VARCHAR(20)   NOT NULL COMMENT 'SALE_PRODUCT(구매) / GIFT_PRODUCT(건별 사은품)',
    sale_product_id BIGINT        NULL COMMENT 'item_type=SALE_PRODUCT일 때',
    product_id      BIGINT        NULL COMMENT 'item_type=GIFT_PRODUCT일 때',
    channel_product_code VARCHAR(100) NULL COMMENT '채널 상품코드 (마켓 수신값)',
    channel_option_code  VARCHAR(30)  NULL COMMENT '채널 옵션코드 (마켓 수신값, 옵션 없음 = 빈 문자열)',
    quantity        INT           NOT NULL COMMENT '수량',
    unit_price      DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '주문 시점 단가 스냅샷 (사은품은 0)',
    status          VARCHAR(20)   NOT NULL DEFAULT 'ORDERED' COMMENT 'ORDERED / CANCELED (부분취소 단위)',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_user_id BIGINT        NOT NULL COMMENT '등록자',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_user_id BIGINT        NULL COMMENT '마지막 수정자',
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_items_brand FOREIGN KEY (brand_id) REFERENCES brands (id),
    CONSTRAINT fk_order_items_sale_product FOREIGN KEY (sale_product_id) REFERENCES sale_products (id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_order_items_created_user FOREIGN KEY (created_user_id) REFERENCES users (id),
    CONSTRAINT fk_order_items_updated_user FOREIGN KEY (updated_user_id) REFERENCES users (id),
    CONSTRAINT chk_order_items_type_ref CHECK (
        (item_type = 'SALE_PRODUCT' AND product_id IS NULL AND
         (sale_product_id IS NOT NULL OR channel_product_code IS NOT NULL)) OR
        (item_type = 'GIFT_PRODUCT' AND product_id IS NOT NULL AND sale_product_id IS NULL)
        ),
    INDEX idx_order_items_channel_code (channel_product_code, channel_option_code)
) COMMENT '주문 항목 (수량 부분취소는 행 분할로 처리)';

CREATE TABLE shipments
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT        NOT NULL COMMENT '소속 주문',
    brand_id        BIGINT        NOT NULL COMMENT '회차 브랜드 (한 회차 = 단일 브랜드)',
    shipment_no     VARCHAR(50)   NOT NULL COMMENT '출고번호 (표시·스캔·송장용, 예: {order_no}-{round_no})',
    round_no        INT           NOT NULL COMMENT '주문 내 회차 (1, 2, ...)',
    status          VARCHAR(20)   NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED(분리만, 지시 대기) / INSTRUCTED / PICKED / PACKED / PALLETIZED / MASTER_SHIPPED / CANCELED',
    split_reason    VARCHAR(30)   NULL COMMENT '분리 사유 (CUSTOMS_LIMIT / QUANTITY_LIMIT / STOCK_SHORTAGE / BRAND_SPLIT / MANUAL), 단일 회차면 NULL',
    total_amount    DECIMAL(12,2) NULL COMMENT '회차 금액 (통관 신고 참조값, 분리 시 산정)',
    instructed_by   BIGINT        NULL COMMENT '출고지시자 (CREATED 동안 NULL)',
    instructed_at   DATETIME      NULL COMMENT '출고지시 시각 (CREATED 동안 NULL)',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_user_id BIGINT        NOT NULL COMMENT '생성자 (분리 수행자)',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_user_id BIGINT        NULL COMMENT '마지막 수정자',
    CONSTRAINT uk_shipments_no UNIQUE (shipment_no),
    CONSTRAINT uk_shipments_order_round UNIQUE (order_id, round_no),
    CONSTRAINT fk_shipments_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_shipments_brand FOREIGN KEY (brand_id) REFERENCES brands (id),
    CONSTRAINT fk_shipments_instructed_by FOREIGN KEY (instructed_by) REFERENCES users (id),
    CONSTRAINT fk_shipments_created_user FOREIGN KEY (created_user_id) REFERENCES users (id),
    CONSTRAINT fk_shipments_updated_user FOREIGN KEY (updated_user_id) REFERENCES users (id)
) COMMENT '출고 회차 (분리·지시·물류 작업의 단위)';

CREATE TABLE shipment_items
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    shipment_id   BIGINT NOT NULL COMMENT '소속 회차',
    order_item_id BIGINT NOT NULL COMMENT '대상 주문 항목',
    quantity      INT    NOT NULL COMMENT '이 회차에 나가는 수량',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_user_id BIGINT NOT NULL COMMENT '등록자',
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_user_id BIGINT NULL COMMENT '마지막 수정자',
    CONSTRAINT uk_shipment_items_target UNIQUE (shipment_id, order_item_id),
    CONSTRAINT fk_shipment_items_shipment FOREIGN KEY (shipment_id) REFERENCES shipments (id),
    CONSTRAINT fk_shipment_items_order_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_shipment_items_created_user FOREIGN KEY (created_user_id) REFERENCES users (id),
    CONSTRAINT fk_shipment_items_updated_user FOREIGN KEY (updated_user_id) REFERENCES users (id)
) COMMENT '회차별 출고 항목';

CREATE TABLE order_status_history
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT      NOT NULL COMMENT '소속 주문 (SHIPMENT 변경도 주문 축으로 조회)',
    target_type     VARCHAR(20) NOT NULL COMMENT 'ORDER / SHIPMENT',
    target_id       BIGINT      NOT NULL COMMENT 'orders.id 또는 shipments.id (이형 참조, FK 미설정)',
    previous_status VARCHAR(20) NULL COMMENT '이전 상태 (최초 생성 시 NULL)',
    current_status  VARCHAR(20) NOT NULL COMMENT '변경 후 상태',
    changed_by      BIGINT      NOT NULL COMMENT '변경자 (시스템 전이는 SYSTEM 계정)',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_status_history_changed_by FOREIGN KEY (changed_by) REFERENCES users (id)
) COMMENT '주문/출고 상태 변경 이력 (append-only)';