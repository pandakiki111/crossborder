-- =====================================================
-- V2: 상품/재고 도메인
--
-- sales_channels                판매채널 마스터 (RAKUTEN / QOO10 / AMAZON_JP ...)
-- customs_categories            통관 품목 분류 (수량 한도는 상품 개별이 아니라
--                               분류 단위 합산으로 판정 — 브랜드가 달라도 같은
--                               분류면 합산. 예: 시트마스크 50매)
-- products                      제품 = 재고 관리 단위 (SKU). 물리/할당 재고 보유,
--                               판매가능재고 = physical - allocated (계산값)
--                               통관 정보(HS Code, 분류, 원산지)와 물리 정보(무게·치수) 포함
-- sale_products                 판매상품 = 판매 단위 (마켓 노출 단위)
--                               단품도 구성 1행짜리 판매상품으로 등록 (판매 경로 단일화)
-- sale_product_items            판매상품 구성 (제품 N:M + 수량, 구성 고정 사은품 포함)
-- sale_product_channel_mappings 채널별 상품/옵션 코드 매핑
--                               (channel, code, option_code) → 판매상품 1개 확정이
--                               주문 수집의 관문. 옵션코드는 채널 종속값이라 여기에만 존재
-- stock_movements               물리 재고 원장 (append-only, 모든 증감의 근거 기록)
-- channel_inventory_sync        채널별 재고 전송 상태 (채널×제품당 1행 유지,
--                               실패 감지와 재시도의 입력값)
--
-- 핵심 규칙 (서비스 레이어 보장):
--  - 모든 판매는 sale_products를 통한다 (products 직접 판매 없음)
--  - physical_stock의 모든 증감은 stock_movements에 선기록 (원장이 진실의 원천)
--  - allocated_stock은 주문 상태에서 유도 (원장 대상 아님, 정합성은 배치 대조)
--  - 재고 변동 시 관련 채널에 판매가능재고 재전송 (sync 테이블이 상태 추적)
-- =====================================================

CREATE TABLE sales_channels
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(30)  NOT NULL COMMENT '채널 코드 (RAKUTEN / QOO10 / AMAZON_JP ...)',
    name       VARCHAR(100) NOT NULL COMMENT '채널 표시명',
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / INACTIVE',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_sales_channels_code UNIQUE (code)
) COMMENT '판매채널 마스터';

CREATE TABLE customs_categories
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(30)  NOT NULL COMMENT '분류 코드 (SHEET_MASK / COSMETIC_LIQUID ...)',
    name       VARCHAR(100) NOT NULL COMMENT '분류명 (예: 시트마스크)',
    qty_limit  INT          NULL COMMENT '1회 통관 수량 한도 (예: 시트마스크 50). NULL이면 수량 한도 없음',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_customs_categories_code UNIQUE (code)
) COMMENT '통관 품목 분류 (수량 한도는 이 분류 단위로 합산 판정)';

CREATE TABLE products
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    brand_id        BIGINT        NOT NULL COMMENT '제품 소속 브랜드',
    sku             VARCHAR(50)   NOT NULL COMMENT '내부 재고 관리 코드 (SKU)',
    name            VARCHAR(300)  NOT NULL COMMENT '제품명 - 관리자 확인',
    name_eng        VARCHAR(300)  NOT NULL COMMENT '영문제품명 - 실제 출고시 사용',
    customs_category_id BIGINT    NULL COMMENT '통관 품목 분류 (NULL이면 수량 한도 대상 아님)',
    hs_code         VARCHAR(20)   NULL COMMENT 'HS Code (일본 수입통관 기준, 다국가 반영 시 별도 테이블 분리)',
    unit_price      DECIMAL(12,2) NULL COMMENT '기준단가 (OMS 내 비율계산용)',
    currency        CHAR(3)       NULL COMMENT 'ISO 통화 코드 (현재 JPY 기준)',
    barcode         VARCHAR(50)   NULL COMMENT '스캔용 바코드 (JAN/EAN)',
    physical_stock  INT           NOT NULL DEFAULT 0 COMMENT '물리재고 (단일창고 전제)',
    allocated_stock INT           NOT NULL DEFAULT 0 COMMENT '할당재고 (미출고 주문분)',
    weight_g        DECIMAL(10,3) NULL COMMENT '무게(g)',
    width_cm        DECIMAL(10,2) NULL COMMENT '가로(cm)',
    length_cm       DECIMAL(10,2) NULL COMMENT '세로(cm)',
    height_cm       DECIMAL(10,2) NULL COMMENT '높이(cm)',
    origin          VARCHAR(50)   NULL COMMENT '원산지',
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / INACTIVE',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_user_id BIGINT        NOT NULL COMMENT '최초 등록자',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_user_id BIGINT        NULL COMMENT '마지막 수정자',
    CONSTRAINT uk_products_sku UNIQUE (sku),
    CONSTRAINT uk_products_barcode UNIQUE (barcode),
    CONSTRAINT fk_products_brand FOREIGN KEY (brand_id) REFERENCES brands (id),
    CONSTRAINT fk_products_created_user FOREIGN KEY (created_user_id) REFERENCES users (id),
    CONSTRAINT fk_products_updated_user FOREIGN KEY (updated_user_id) REFERENCES users (id),
    CONSTRAINT fk_products_customs_category FOREIGN KEY (customs_category_id) REFERENCES customs_categories (id)
    -- FULLTEXT (name): 검색 기능 구현 시 ngram 파서와 함께 별도 마이그레이션으로 추가
) COMMENT '제품 (재고 관리 단위, SKU)';

CREATE TABLE sale_products
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    brand_id        BIGINT        NOT NULL COMMENT '판매상품 소속 브랜드',
    name            VARCHAR(300)  NOT NULL COMMENT '판매상품명',
    code            VARCHAR(100)  NOT NULL COMMENT '판매상품코드',
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / INACTIVE',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_user_id BIGINT        NOT NULL COMMENT '최초 등록자',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_user_id BIGINT        NULL COMMENT '마지막 수정자',
    CONSTRAINT fk_sale_products_brand FOREIGN KEY (brand_id) REFERENCES brands (id),
    CONSTRAINT fk_sale_products_created_user FOREIGN KEY (created_user_id) REFERENCES users (id),
    CONSTRAINT fk_sale_products_updated_user FOREIGN KEY (updated_user_id) REFERENCES users (id)
) COMMENT '판매상품 관리';

CREATE TABLE sale_product_items
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    sale_product_id BIGINT        NOT NULL COMMENT '연결된 판매상품',
    product_id      BIGINT        NOT NULL COMMENT '제품',
    quantity        INT           NOT NULL DEFAULT 1 COMMENT '제품 수량',
    is_gift         BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '사은품 여부 (재고 차감 대상, 매출 제외, 수입 통관시 사은품 가격처리)',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_user_id BIGINT        NOT NULL COMMENT '최초 등록자',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_user_id BIGINT        NULL COMMENT '마지막 수정자',
    CONSTRAINT uk_sale_product_items_composition UNIQUE (sale_product_id,product_id,is_gift),
    CONSTRAINT fk_sale_product_items_sale_products FOREIGN KEY (sale_product_id) REFERENCES sale_products (id),
    CONSTRAINT fk_sale_product_items_products FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_sale_product_items_created_user FOREIGN KEY (created_user_id) REFERENCES users (id),
    CONSTRAINT fk_sale_product_items_updated_user FOREIGN KEY (updated_user_id) REFERENCES users (id)
) COMMENT '판매상품 구성 (제품 매핑)';

CREATE TABLE sale_product_channel_mappings
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    sale_product_id BIGINT        NOT NULL COMMENT '매핑할 판매상품',
    channel_id      BIGINT        NOT NULL COMMENT '판매채널',
    code            VARCHAR(100)   NOT NULL COMMENT '판매채널에 등록된 상품코드',
    option_code     VARCHAR(30)   NOT NULL DEFAULT '' COMMENT '판매채널에 등록한 옵션코드',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_user_id BIGINT        NOT NULL COMMENT '최초 등록자',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_user_id BIGINT        NULL COMMENT '마지막 수정자',
    CONSTRAINT uk_sale_product_channel_mappings_composition UNIQUE (channel_id, code, option_code),
    CONSTRAINT fk_sale_product_channel_mappings_sale_products FOREIGN KEY (sale_product_id) REFERENCES sale_products (id),
    CONSTRAINT fk_sale_product_channel_mappings_channels FOREIGN KEY (channel_id) REFERENCES sales_channels (id),
    CONSTRAINT fk_sale_product_channel_mappings_created_user FOREIGN KEY (created_user_id) REFERENCES users (id),
    CONSTRAINT fk_sale_product_channel_mappings_updated_user FOREIGN KEY (updated_user_id) REFERENCES users (id)
) COMMENT '판매상품-채널 코드 매핑';

CREATE TABLE stock_movements
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id      BIGINT          NOT NULL COMMENT '제품',
    movement_type   VARCHAR(20)     NOT NULL COMMENT '수량 변경 사유. INBOUND(입고) / OUTBOUND(출고) / CANCEL_RESTORE(취소 복원) / ADJUST(실사·파손·폐기 등 수동 조정)',
    quantity        int             not null COMMENT '변동 수량 (부호 포함: 입고 +, 출고 -)',
    reference_type  VARCHAR(20)     NULL COMMENT '변동 근거 문서 유형 (SHIPMENT / ORDER / MANUAL). 이형 참조라 FK 미설정, 무결성은 서비스 레이어에서 보장',
    reference_id    BIGINT          NULL COMMENT '근거 문서 id (reference_type의 테이블 기준, MANUAL이면 NULL)',
    note            VARCHAR(100)    NULL COMMENT '메모',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_user_id BIGINT          NOT NULL COMMENT '최초 등록자',
    CONSTRAINT fk_stock_movements_product_id FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_stock_movements_created_user FOREIGN KEY (created_user_id) REFERENCES users (id)
) COMMENT '물리 재고 원장';

CREATE TABLE channel_inventory_sync
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_id         BIGINT      NOT NULL COMMENT '판매채널',
    product_id         BIGINT      NOT NULL COMMENT '제품',
    last_sent_quantity INT         NULL COMMENT '채널에 마지막으로 전송한 판매가능재고 (전송 전이면 NULL)',
    sync_status        VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING(전송 대기) / SUCCESS / FAILED',
    fail_reason        VARCHAR(300) NULL COMMENT '실패 사유 (sync_status=FAILED일 때)',
    last_synced_at     DATETIME    NULL COMMENT '마지막 전송 시도 시각',
    created_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_channel_inventory_sync_target UNIQUE (channel_id, product_id),
    CONSTRAINT fk_channel_inventory_sync_channel FOREIGN KEY (channel_id) REFERENCES sales_channels (id),
    CONSTRAINT fk_channel_inventory_sync_product FOREIGN KEY (product_id) REFERENCES products (id)
) COMMENT '채널별 재고 전송 상태 (채널×제품당 1행 유지, 실패 감지·재시도의 입력값)';