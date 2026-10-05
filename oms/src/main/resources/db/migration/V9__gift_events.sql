-- =====================================================
-- V9: 이벤트 사은품 (자동 증정)
--
-- orders.paid_at                결제 시각 (마켓 수신값). 이벤트 기준 시각(time_basis=PAID)용. NULL이면 ordered_at으로 판정
-- order_items.gift_source       사은품 항목의 출처: COLLECTED(채널 수신·시딩) / EVENT(이벤트 자동 증정) / MANUAL(운영자 수동)
-- order_items.gift_event_id     EVENT 사은품의 이벤트
-- gift_events                   이벤트 정의 (브랜드 단위). 조건은 행, 판정은 단일 파이프라인(GiftEventEvaluator)
--                               code = 서버 생성 이벤트 코드 GEVT-XXXXXXXX (운영자가 부르는 식별자, 생성 후 불변)
-- gift_event_conditions         상품 조건 (판매상품코드[+옵션] 또는 SKU). 행이 없으면 상품 조건 없음 (금액만)
-- gift_event_items              증정 품목 (제품 = SKU 단위). 순차 우선순위·한도·소진 집계
-- gift_event_grants             (이벤트, 주문) 증정 기록. 멱등(재수집·재평가 중복 증정 방지)의 기준
-- gift_event_active_periods     활성 구간 이력. 매핑 이력과 같은 규칙 — 반열림 구간, 중단 = 현재 행 마감, 재시작 = 신규 행
--
-- 핵심 규칙 (서비스 레이어 보장):
--  - 판정 기준 시각 t = time_basis에 따라 ordered_at 또는 paid_at(NULL이면 ordered_at). 처리(수집) 시각과 무관
--  - 대상: t ∈ [starts_at, ends_at) AND t가 활성 구간 중 하나에 포함 AND grants에 (이벤트, 주문) 없음
--  - 증정 수량 N = FIXED: fixed_qty / PER_QUANTITY: 조건 상품 구매수량 몫 × per_qty_give
--    (COMBINED = 합산 후 몫, PER_PRODUCT = 조건별 몫의 합)
--  - 품목: ALWAYS = 전 품목 N개씩 / SEQUENTIAL = N개를 한 품목으로 전부 충당 가능한 최선순위 1종 /
--    RANDOM = N개 충당 가능한 품목 중 1종 랜덤
--  - 매핑안됨 주문도 판정한다: 매핑안됨 항목은 조건 매칭에서만 빠지고 금액 조건은 그대로. 매핑 완료 후 재평가하지 않는다
--  - 지급 이력(grants)이 있는 이벤트는 수정·삭제 불가 (중단·재시작은 가능)
--  - 상태(ACTIVE/STOPPED/ENDED)는 저장하지 않고 활성 구간·ends_at에서 파생한다 (이중 관리 없음)
-- =====================================================

ALTER TABLE orders
    ADD COLUMN paid_at DATETIME NULL COMMENT '결제 시각 (마켓 수신값, 시딩 미입력 시 ordered_at). 이벤트 PAID 기준' AFTER ordered_at,
    ALGORITHM = INSTANT;

CREATE TABLE gift_events
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    code            VARCHAR(13)   NOT NULL COMMENT '이벤트 코드 GEVT- + 혼동 문자(0·O·1·I·L) 뺀 8자리. 서버 생성, 불변',
    brand_id        BIGINT        NOT NULL COMMENT '이벤트 브랜드 (조건·증정 품목 모두 이 브랜드)',
    name            VARCHAR(200)  NOT NULL COMMENT '이벤트명 (운영 표시, 다운로드 사은품이벤트 열)',
    time_basis      VARCHAR(20)   NOT NULL COMMENT 'ORDERED / PAID — 판정 기준 시각',
    starts_at       DATETIME      NOT NULL COMMENT '기간 시작 (포함)',
    ends_at         DATETIME      NOT NULL COMMENT '기간 끝 (미포함)',
    amount_min      DECIMAL(12,2) NULL COMMENT '결제금액 하한 (포함, NULL = 없음)',
    amount_max      DECIMAL(12,2) NULL COMMENT '결제금액 상한 (미포함, NULL = 없음)',
    condition_mode  VARCHAR(10)   NOT NULL COMMENT 'ALL / ANY — 상품 조건 결합',
    grant_type      VARCHAR(20)   NOT NULL COMMENT 'ALWAYS / SEQUENTIAL / RANDOM',
    quantity_mode   VARCHAR(20)   NOT NULL COMMENT 'FIXED / PER_QUANTITY',
    fixed_qty       INT           NULL COMMENT 'FIXED 증정 수량',
    per_qty_unit    INT           NULL COMMENT 'PER_QUANTITY: 조건 상품 몇 개마다',
    per_qty_give    INT           NULL COMMENT 'PER_QUANTITY: 몇 개 증정',
    aggregation     VARCHAR(20)   NULL COMMENT 'PER_QUANTITY: COMBINED(합산) / PER_PRODUCT(조건별)',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_user_id BIGINT        NOT NULL COMMENT '등록자',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_user_id BIGINT        NULL COMMENT '마지막 수정자',
    CONSTRAINT uk_gift_events_code UNIQUE (code),
    CONSTRAINT fk_gift_events_brand FOREIGN KEY (brand_id) REFERENCES brands (id),
    CONSTRAINT fk_gift_events_created_user FOREIGN KEY (created_user_id) REFERENCES users (id),
    CONSTRAINT fk_gift_events_updated_user FOREIGN KEY (updated_user_id) REFERENCES users (id),
    CONSTRAINT chk_gift_events_period CHECK (starts_at < ends_at),
    CONSTRAINT chk_gift_events_amount CHECK (amount_min IS NULL OR amount_max IS NULL OR amount_min < amount_max),
    CONSTRAINT chk_gift_events_quantity CHECK (
        (quantity_mode = 'FIXED' AND fixed_qty >= 1 AND per_qty_unit IS NULL AND per_qty_give IS NULL
            AND aggregation IS NULL) OR
        (quantity_mode = 'PER_QUANTITY' AND fixed_qty IS NULL AND per_qty_unit >= 1 AND per_qty_give >= 1
            AND aggregation IS NOT NULL)
        ),
    INDEX idx_gift_events_brand_period (brand_id, starts_at, ends_at)
) COMMENT '사은품 이벤트 (조건은 행, 판정은 단일 파이프라인)';

CREATE TABLE gift_event_conditions
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    gift_event_id     BIGINT       NOT NULL,
    target_type       VARCHAR(20)  NOT NULL COMMENT 'SALE_PRODUCT / SKU',
    sale_product_code VARCHAR(100) NULL COMMENT 'SALE_PRODUCT: 판매상품코드',
    option_code       VARCHAR(30)  NULL COMMENT 'SALE_PRODUCT: 채널 옵션코드. NULL = 옵션 무관',
    sku               VARCHAR(50)  NULL COMMENT 'SKU: 이 제품이 구성에 든 판매상품 구매',
    -- 유니크 비교용 (NULL은 유니크 비교에서 빠지므로 옵션 무관(NULL)을 별도 표식으로 바꾼다)
    target_key        VARCHAR(200) AS (IF(target_type = 'SKU', sku,
                          CONCAT(sale_product_code, CHAR(31), IFNULL(option_code, CHAR(30))))) PERSISTENT,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_user_id   BIGINT       NOT NULL,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_user_id   BIGINT       NULL,
    CONSTRAINT fk_gift_event_conditions_event FOREIGN KEY (gift_event_id) REFERENCES gift_events (id),
    CONSTRAINT fk_gift_event_conditions_created_user FOREIGN KEY (created_user_id) REFERENCES users (id),
    CONSTRAINT fk_gift_event_conditions_updated_user FOREIGN KEY (updated_user_id) REFERENCES users (id),
    CONSTRAINT uk_gift_event_conditions_target UNIQUE (gift_event_id, target_type, target_key),
    CONSTRAINT chk_gift_event_conditions_target CHECK (
        (target_type = 'SALE_PRODUCT' AND sale_product_code IS NOT NULL AND sku IS NULL) OR
        (target_type = 'SKU' AND sku IS NOT NULL AND sale_product_code IS NULL AND option_code IS NULL)
        )
) COMMENT '사은품 이벤트 상품 조건';

CREATE TABLE gift_event_items
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    gift_event_id   BIGINT   NOT NULL,
    product_id      BIGINT   NOT NULL COMMENT '증정 제품 (SKU 단위)',
    priority        INT      NOT NULL COMMENT 'SEQUENTIAL 순서 (작을수록 먼저)',
    limit_qty       INT      NULL COMMENT 'SEQUENTIAL·RANDOM 증정 한도 (NULL = 무제한, ALWAYS는 NULL)',
    granted_qty     INT      NOT NULL DEFAULT 0 COMMENT '증정 누계 (이벤트 락 아래 원자적 증가, 항목 취소로 줄지 않음)',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_user_id BIGINT   NOT NULL,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_user_id BIGINT   NULL,
    CONSTRAINT fk_gift_event_items_event FOREIGN KEY (gift_event_id) REFERENCES gift_events (id),
    CONSTRAINT fk_gift_event_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_gift_event_items_created_user FOREIGN KEY (created_user_id) REFERENCES users (id),
    CONSTRAINT fk_gift_event_items_updated_user FOREIGN KEY (updated_user_id) REFERENCES users (id),
    CONSTRAINT uk_gift_event_items_product UNIQUE (gift_event_id, product_id),
    CONSTRAINT chk_gift_event_items_qty CHECK (granted_qty >= 0 AND (limit_qty IS NULL OR limit_qty >= 1))
) COMMENT '사은품 이벤트 증정 품목';

CREATE TABLE gift_event_grants
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    gift_event_id   BIGINT   NOT NULL,
    order_id        BIGINT   NOT NULL,
    granted_at      DATETIME NOT NULL COMMENT '증정 처리 시각',
    created_user_id BIGINT   NOT NULL COMMENT '처리자 (시딩 업로더, 수집은 SYSTEM)',
    CONSTRAINT fk_gift_event_grants_event FOREIGN KEY (gift_event_id) REFERENCES gift_events (id),
    CONSTRAINT fk_gift_event_grants_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_gift_event_grants_created_user FOREIGN KEY (created_user_id) REFERENCES users (id),
    CONSTRAINT uk_gift_event_grants_event_order UNIQUE (gift_event_id, order_id),
    INDEX idx_gift_event_grants_order (order_id)
) COMMENT '사은품 이벤트 증정 기록 (주문당 이벤트 1회)';

CREATE TABLE gift_event_active_periods
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    gift_event_id   BIGINT   NOT NULL,
    active_from     DATETIME NOT NULL COMMENT '활성 시작 (포함)',
    active_to       DATETIME NULL COMMENT '활성 끝 (미포함). NULL이면 현재 활성',
    current_key     TINYINT AS (IF(active_to IS NULL, 1, NULL)) PERSISTENT COMMENT '열린 구간 유니크용',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_user_id BIGINT   NOT NULL,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_user_id BIGINT   NULL,
    CONSTRAINT fk_gift_event_active_periods_event FOREIGN KEY (gift_event_id) REFERENCES gift_events (id),
    CONSTRAINT fk_gift_event_active_periods_created_user FOREIGN KEY (created_user_id) REFERENCES users (id),
    CONSTRAINT fk_gift_event_active_periods_updated_user FOREIGN KEY (updated_user_id) REFERENCES users (id),
    -- 열린 구간은 이벤트당 최대 1개 (동시 재시작으로 둘 생기는 것을 DB가 막는다)
    CONSTRAINT uk_gift_event_active_periods_current UNIQUE (gift_event_id, current_key),
    CONSTRAINT chk_gift_event_active_periods CHECK (active_to IS NULL OR active_from < active_to)
) COMMENT '사은품 이벤트 활성 구간 이력 (중단 = 마감, 재시작 = 신규)';

-- ---------------------------------------------------------------------
-- order_items 변경은 운영 테이블(최대 수천만 행)이라 쓰기를 막지 않는 방식만 쓴다
--  - 컬럼 추가: ALGORITHM=INSTANT (메타데이터만 변경)
--  - FK 추가: foreign_key_checks=0 이면 INPLACE·LOCK=NONE 가능. 새 컬럼이 전부 NULL이라 검사를 건너뛰어도 위반 행이 없다
--  - 백필: 사은품 행만 UPDATE (행 잠금, 테이블 잠금 아님)
--  - gift_source CHECK 제약은 두지 않는다 (의도적 제외): CHECK 추가는 MariaDB에서 ALGORITHM=COPY만 지원해
--    테이블 재작성 동안 쓰기가 막힌다 (로컬 항목 210만 건에서 V9 전체 24.5초 실측). 규칙
--    "GIFT_PRODUCT면 gift_source 필수, EVENT면 gift_event_id 필수, 구매 항목은 둘 다 NULL"은
--    OrderItem 생성 메서드가 유일한 강제 지점이다. 백필 결과는 V9MigrationTest가 검증한다
-- ---------------------------------------------------------------------
ALTER TABLE order_items
    ADD COLUMN gift_source VARCHAR(20) NULL COMMENT '사은품 출처: COLLECTED / EVENT / MANUAL (GIFT_PRODUCT 필수 — DB CHECK 없음, OrderItem이 강제)' AFTER product_id,
    ADD COLUMN gift_event_id BIGINT NULL COMMENT 'EVENT 사은품의 이벤트' AFTER gift_source,
    ALGORITHM = INSTANT;

SET foreign_key_checks = 0;
ALTER TABLE order_items
    ADD CONSTRAINT fk_order_items_gift_event FOREIGN KEY (gift_event_id) REFERENCES gift_events (id),
    ALGORITHM = INPLACE, LOCK = NONE;
SET foreign_key_checks = 1;

-- 기존 사은품 행은 전부 채널 수신·시딩분
UPDATE order_items SET gift_source = 'COLLECTED' WHERE item_type = 'GIFT_PRODUCT' AND gift_source IS NULL;
