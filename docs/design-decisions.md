# 설계 결정 (Design Decisions)

이 문서는 구현 시 반드시 따라야 하는 확정 결정이다.
여기 없는 설계 판단이 필요하면 임의로 정하지 말고 질문으로 올릴 것.

## 1. 모듈 구조

- common / infra / auth / oms / cbt / batch. 의존 방향은 아래로만:
  실행 모듈(auth·oms·cbt·batch) → infra → common. 실행 모듈 간 참조 금지.
- common: 엔티티·enum·공용 예외. 스프링 의존은 compileOnly 어노테이션까지만
  (jakarta.persistence-api, spring-data-commons, spring-data-jpa, querydsl).
- infra: 기술 의존(JPA, Redis/Redisson, Flyway, QueryDSL 실행, JWT).
  설정은 @AutoConfiguration + AutoConfiguration.imports로 제공 (컴포넌트 스캔 비의존).
  EntityScan은 infra가 중앙 제공하되, 스캔 범위는 모듈이 설정(crossborder.jpa.entity-packages)으로 선언한다
  (기본: common.entity 전체 — oms·auth·batch는 설정 없음). cbt는 자기 엔티티 패키지(common.entity.cbt)만
  선언하며 oms 엔티티를 로드하지 않는다. 실행 모듈에 @EntityScan을 두지 않는다 (설정 범위를 덮어쓴다).
- Repository는 실행 모듈 소유. 각 모듈이 자기 패키지로 @EnableJpaRepositories 명시.
  광역("com.crossborder") 선언 금지. 공통 조회가 실제로 겹칠 때만 infra 승격.
- 라이브러리 노출 정책: infra에서 JPA·Redis 스타터는 api, Redisson은 implementation
  (실행 모듈이 RedissonClient를 직접 만지지 못하게 — 락은 infra 래핑으로만).
  분산 락은 infra `DistributedLockManager`로만 노출한다: 키를 정렬·중복 제거해 멀티락으로 한 번에 획득(데드락 방지),
  대기·임대 시간은 crossborder.lock.wait-time / lease-time (기본 3s / 30s), 실패는 LockAcquisitionException.
  lease 30s 근거 (2026-10-04 실측, 로컬): 시딩 묶음(500주문 INSERT+할당) 평균 약 35ms, 소급 묶음 약 17ms,
  매핑 완료 트랜잭션(매핑 1건이 주문 405건 확정·할당) API 전체 0.52s → 최악 관측치의 약 60배 여유.
  lease를 명시하면 Redisson watchdog 자동 연장이 꺼지므로 작업이 lease보다 길면 락이 먼저 풀린다(해제 시 경고 로그).
  반대로 보유 프로세스가 죽으면 해당 제품은 최대 lease만큼 막힌다 — 그래서 수 분 단위가 아니라 30s로 둔다.
  jjwt도 동일: infra 캡슐화, 자체 타입(TokenPayload 등)만 노출.
- oms와 cbt는 독립 시스템이다. 별도 DB를 사용하며(로컬은 같은 MariaDB 인스턴스의
  별도 스키마: crossborder / crossborder_cbt), 서로의 테이블을 직접 읽거나 쓰지 않는다.
  로컬 cbt 계정(cbt)은 crossborder_cbt에만 권한이 있어 DB 권한으로도 경계를 강제한다
  (docker/mariadb/init/01-cbt-schema.sql).
- oms → cbt 인계는 명시적 "출고 접수" API 호출이다 (DB·상태 공유 아님).
  oms는 물류 연동을 LogisticsClient 인터페이스로 추상화하고, 자사 cbt 구현체와
  외부 물류시스템 구현체(추후 mock)를 동일 계약으로 교체 가능하게 한다.
  고객사(company) 설정으로 어느 물류 시스템을 쓰는지 정해지는 구조를 전제한다.
- cbt → oms 역방향(작업 상태 통지: 피킹·패킹·출하 완료)도 API 콜백으로 한다.
- WORKER는 cbt 전용 사용자다. oms의 모든 API에서 WORKER를 차단한다.
  cbt 접근은 기기 신뢰 인증(2차)과 함께 개방한다.
- Flyway 소유권: oms DB는 oms가, cbt DB는 cbt가 자기 마이그레이션을 소유한다.
  auth/batch는 spring.flyway.enabled=false.

## 2. 스키마 / 마이그레이션

- 스키마 변경은 Flyway 마이그레이션으로만. GUI·직접 DDL 금지. ddl-auto: validate.
- 적용된 V 파일은 불변, 변경은 새 버전으로 전진. 로컬 미공유 상태만 리셋 허용.
- 테스트 데이터는 R__(Repeatable) + local 프로파일 locations 분리. 멱등 작성
  (ON DUPLICATE KEY UPDATE, INSERT IGNORE 금지 — CHECK 위반을 삼킨다).
- 네이밍: 테이블 복수 snake_case / PK는 id / FK는 참조단수_id /
  제약은 fk_·uk_·chk_ 접두 명명 필수 (익명 제약 금지).
- 상태·유형 컬럼은 VARCHAR + Java enum(@Enumerated(STRING)). DB ENUM·INT 코드 금지.
- 불리언은 BOOLEAN (CHAR Y/N 금지).
- 외부 발급 코드(마켓 상품·옵션코드, HS 등)는 전부 VARCHAR.
- 감사 컬럼: created_at/updated_at + created_user_id/updated_user_id(nullable).
  JPA Auditing + AuditorContext(ThreadLocal, 요청 필터가 set/clear) + 폴백 SYSTEM(id=1).
- append-only 테이블(stock_movements, login_histories, order_status_history)은
  updated_at/updated_user_id 없음.
- soft delete: 마스터(users, companies, brands, products, sale_products)는
  물리 삭제 금지, status(+deleted_at)로. FK는 유지(RESTRICT).
  탈퇴 시 개인정보 마스킹·login_id 변형 반납은 서비스/배치 책임.

## 3. 조직 / 인증

- users 단일 테이블 + role(ADMIN/WORKER/COMPANY_STAFF/BRAND_STAFF).
  role별 소속 조합은 DB CHECK + 엔티티 정적 팩토리로 이중 강제.
- 로그인 식별자는 login_id (관리자군=이메일 값, WORKER=작업자코드).
- 인증: JWT. auth가 발급, oms가 필터로 검증. claims: userId, role, companyId, brandId.
- JWT 필터는 "있으면 검증, 없으면 침묵". 401 결정은 SecurityConfig 인가 규칙 +
  HttpStatusEntryPoint. /error, /actuator/health는 permitAll.
- 역할(role) 검사 = SecurityConfig URL 규칙에 중앙 관리.
  oms는 전 API가 ADMIN·COMPANY_STAFF·BRAND_STAFF만 허용 (WORKER 403, §1).
  ADMIN 전용 URL 규칙은 해당 API가 생길 때 추가한다 (엔드포인트 없는 예약 규칙을 두지 않는다).
- 스코프(소속) 검사 = `ScopePolicy` 단일 구현 (oms `security.scope`).
  - 기준은 항상 인증 사용자(principal)의 companyId·brandId. 요청이 보낸 회사·브랜드 선언은 믿지 않는다
    (그래서 요청 파라미터로 companyId를 받지 않는다 — brandId만 받고 회사는 브랜드에서 유도).
  - 브랜드: ADMIN 전체 / COMPANY_STAFF 자사 브랜드 / BRAND_STAFF 자기 브랜드 / WORKER 불가.
  - 주문: ADMIN 전체 / COMPANY_STAFF 자사 주문(orders.company_id) /
    BRAND_STAFF 자기 브랜드 항목이 있는 주문 / WORKER 불가.
  - WORKER는 SecurityConfig에서 먼저 막히므로 ScopePolicy의 WORKER 불가는 이중 방어다.
  - 선언 방식: 요청 파라미터로 들어온 id는 서비스 메서드에 `@ScopeCheck(BRAND|ORDER)` + 파라미터 `@ScopeId`
    (ScopeCheckAspect가 트랜잭션 전에 검사). 엔티티에서 유도한 id(매핑·판매상품의 brandId)는
    `ScopePolicy.canAccessBrand`를 직접 호출.
  - 존재 확인 → 스코프 확인 순. 없으면 404, 권한 없으면 403 — 404 위장 금지 (브랜드·매핑·주문 전부).
    id는 운영자 간 비밀이 아니고, "없다"는 응답은 마스터 데이터를 의심하게 만드는 오도 메시지다.
  - 주문 목록 조회의 스코프 조건(OrderQueryRepository.scope)은 같은 규칙을 QueryDSL 조건으로 옮긴 것.
    규칙 변경 시 함께 바꾼다. 일치는 OrderScopeConsistencyTest(실제 MariaDB, Testcontainers)가 검증한다.
- WORKER 로그인은 기기 신뢰 인증(2차) 구현 전까지 차단.
  관리자군 TOTP도 2차. 상세는 docs/device-registration.md.
- login_histories는 성공 로그인만 기록.
- 실패 응답은 원인 무구분 동일 메시지(계정 존재 비노출). WORKER 차단만 정책 메시지.

## 4. 상품 / 재고

- products = 재고 관리 단위(SKU, UNIQUE). sale_products = 판매 단위.
  모든 판매는 sale_products 경유 (products 직접 판매 없음).
  단품도 구성 1행짜리 판매상품.
- sale_products.code는 내부 식별자, 생성 후 불변.
- 구성(sale_product_items)은 주문 이력 발생 후 변경 금지 (서비스 검증 필수).
  구성 리뉴얼 = 새 판매상품 등록(새 code) + 채널 매핑 재지정 + 구 상품 INACTIVE.
  이 절차를 한 번의 운영 액션으로 묶는 "리뉴얼 액션" 제공.
- 주문의 제품 전개는 소비 시점(할당·피킹)에 수행 (지연 전개).
  구성 불변이 지연 전개의 안전 전제다.
- 채널 매핑(sale_product_channel_mappings)은 시간 이력:
  effective_from/effective_to. 재지정 = 구 행 마감 + 신 행 추가 (UPDATE 금지).
  주문 매칭은 수집 시각이 아니라 ordered_at 기준 구간 조회
  (배치 지연·재수집에도 결과 불변).
  옵션코드 없음은 NULL이 아니라 '' 정규화.
- 채널 매핑 유니크는 브랜드 단위 (channel, brand, code, option_code).
  브랜드마다 마켓 스토어가 따로라 채널 상품코드가 브랜드 간에 겹칠 수 있다.
  mappings.brand_id는 판매상품 브랜드의 복사값 — 브랜드 범위 판정의 기준은 sale_products.brand_id,
  복사값은 인덱스용.
- 재고: 단일 창고 전제. products.physical_stock / allocated_stock.
  판매가능 = physical - allocated (계산값).
  physical의 모든 증감은 stock_movements(원장, 부호 포함 수량)에 기록.
  allocated는 주문 상태에서 유도, 원장 비대상.
- 재고 할당 (allocated_stock):
  - 전개: SALE_PRODUCT 항목 = 판매상품 구성 × 주문수량 (구성 고정 사은품 is_gift 포함), GIFT_PRODUCT = 제품 직접.
    유효(ORDERED) 항목만. 판매가능 음수 허용 — 한도 검사 없이 증가 (physical은 음수 금지 유지).
  - 증감은 원자적 UPDATE(allocated_stock ± ?)로, 제품 락(stock:product:{productId}) 아래 트랜잭션 안에서 한다.
    락은 커밋 후 해제 (executeWithLocks로 트랜잭션을 감싸거나, 열린 트랜잭션이면 lockUntilTransactionEnd).
  - orders.allocated_at = 할당 완료 시각 (V6). 할당은 allocated_at IS NULL인 주문에만 하고 같은 트랜잭션에서 기록 → 멱등.
  - 묶음 실행 규칙은 ChunkedAllocationExecutor 하나로 등록(시딩)과 소급이 공유한다: 500주문 묶음 → 제품 합산 정렬 멀티락 1회
    → 새 트랜잭션 → 커밋 후 해제, 묶음이 락 타임아웃·DB 오류로 실패하면 주문 단위로 재시도해 원인 주문만 실패.
  - 등록 경로(시딩): 묶음 트랜잭션에 INSERT와 할당을 함께 넣는다. 실패 주문은 INSERT까지 롤백되고 결과 파일에 사유.
    등록됐는데 미할당인 주문은 남지 않는다. (주문마다 락·커밋하면 대량 시딩 왕복이 주문 수만큼 늘어나 묶음 단위로 결정)
  - 매핑안됨 주문은 전개할 수 없어 등록 시 할당하지 않는다. 항목 매핑이 확정될 때마다(OrderMappingService) 그 주문의
    유효 항목이 모두 확정됐는지 보고, 모두 확정이면 즉시 주문 전체를 할당하고 allocated_at을 기록한다 (매핑 확정 = 할당 트리거).
    매핑안됨 항목을 취소해 해소된 경우도 취소 처리에서 같은 방식으로 할당한다. 단 전 항목이 취소돼 남은 유효 항목이 없으면
    할당·allocated_at 기록을 하지 않는다 (취소 주문은 allocated_at NULL로 남고 소급 대상에서도 제외).
  - allocated_at은 항상 "주문 전체 할당 완료 시각"이다 (항목 일부만 할당된 주문 상태는 없다).
  - 소급·정합 검증은 관리자 수동 API (ADMIN, /api/admin/allocations, 동기 실행). 소급은 정상 흐름에서 빠진 예외 상황과
    allocated_at 도입 이전 데이터 정리용이다.
    backfill: 대상 = allocated_at IS NULL AND status != 'CANCELED' (취소 주문은 할당할 것이 없어 제외),
    응답 {processed, allocated, skipped, failed, anomalies, anomalyOrderIds}. 매핑안됨 주문은 skipped.
    DELIVERED 주문은 조회되지만 재고 정합 때문에 할당하지 않고 이상 데이터(anomalies)로 집계해 운영자가 확인한다
    (매핑 완료 시 자동 할당). 주문 행을 잠근 뒤 allocated_at IS NULL을 재확인하므로 멱등.
    consistency: 할당 완료 주문의 유효 항목 전개 합 = allocated_stock 비교 + 미할당·매핑안됨 주문 수.
    정합 기대값은 출고 차감이 없는 현재 기준 — 출고 Phase에서 출고분을 빼도록 바꾼다. 주기 실행은 하지 않는다.
- 통관: customs_categories 분류 단위로 수량 한도 합산 판정 (시트마스크 120매).
  products.customs_unit_qty = 재고 1단위당 통관 계수 (10매입 박스=10).
  판정 수량 = Σ(주문수량 × customs_unit_qty), 분류별 그룹핑.
  총 수량 한도(24개)는 분류 무관 전체 합산, 설정값.

## 5. 주문 / 출고

- 마켓 1주문 = orders 1행, 어떤 경우에도 주문 행을 쪼개지 않는다.
  uk(sales_channel_id, channel_order_no)가 수집·시딩 멱등성 키.
- 금액(total_item_amount, paid_amount)은 마켓 수신값 저장. 재계산 검증 안 함.
- brand_id는 order_items에 (주문 시점 복사). company_id는 orders에
  (멀티브랜드여도 회사는 단일 전제).
- order_items: SALE_PRODUCT/GIFT_PRODUCT 이형 참조(CHECK로 택일 강제).
  수량 부분취소는 행 분할. unit_price는 시점 스냅샷(사은품 0).
- 매핑안됨(판매상품 미확정 항목 보유)은 주문 상태가 아니라 orders.mapping_pending 플래그.
  - 상태는 결제·취소·출고 흐름만 표현한다. 매핑 여부와 무관하게 PAID로 시작, 부분취소면 PARTIAL_CANCELED
    → 상태 필터(PAID·PARTIAL_CANCELED)에 매핑안됨 주문도 걸린다. 매핑 여부는 mappingPending 필터로 따로.
  - 매핑안됨 항목은 판매상품 없이 채널 수신 코드(channel_product_code/option_code)와 brand_id를 저장한다.
  - 해당 (채널, 브랜드, 코드, 옵션) 매핑이 등록되면 ordered_at 구간 매핑으로 항목을 확정하고,
    남은 유효 매핑안됨 항목이 없으면 플래그를 내린다.
  - 매핑 완료는 상태 전이가 아니므로 상태 이력을 남기지 않는다 (완료 시점은 업무상 불필요).
  - mapping_pending 주문은 분리·출고지시 대상 아님 (Order.startShipping이 거부).
- 분리·출고 단위는 shipments(회차). 상태:
  CREATED(분리만) → INSTRUCTED → PICKED → PACKED → PALLETIZED → MASTER_SHIPPED / CANCELED.
  한 회차 = 단일 브랜드. shipment_no(표시·스캔용), split_reason 보유.
- 분리와 출고지시는 독립 행위 (분리만 해두고 나중에 지시 가능).
  주문 SHIPPING 전이는 첫 INSTRUCTED 시점.
- 회차 상태 전이 소유권: 생성·instruct()·cancel()까지 oms. PICKED 이후 전이(pick/pack/palletize/masterShip)는
  cbt 콜백 처리에서만 호출한다 — oms 서비스에서 PICKED 이후 전이를 호출하는 코드를 만들지 않는다 (Shipment javadoc).
- 취소 가능 판정은 항목+회차 레벨 (서비스 책임):
  INSTRUCTED 이상 회차에 물린 항목은 취소 불가.
  CREATED 회차 항목 취소 시 회차에서 제거, 빈 회차는 CANCELED.
- 운영자 수동 취소: POST /api/orders/{orderId}/cancellations, body [{orderItemId, quantity}] (주문 전체 취소 = 전 항목 지정).
  - 원자적 — 한 항목이라도 거부되면 전체 거부, 부분 성공 없음. 판정 순서와 응답: 형식 400 → 항목 브랜드 스코프 403
    (ScopePolicy.canAccessBrand, BRAND_STAFF는 자기 브랜드 항목만) → 상태·수량 409. 같은 단계 사유는 모아서 돌려준다.
  - 취소 가능 수량 = 유효 수량 - INSTRUCTED 이상 회차 배정 수량. 이미 CANCELED 항목 거부.
    사은품(GIFT_PRODUCT)은 전체 취소만 (엔티티 규칙). 본품 취소 시 사은품 자동 연동 취소 없음 (운영자가 직접 선택).
  - 처리(한 트랜잭션): 전량 cancel() / 일부 splitCanceled() 행 분할 → 할당 완료 주문이면 취소 수량 전개분만큼 allocated 감소
    → 미배정 수량부터 소진하고 모자라는 만큼 CREATED 회차를 회차 번호 역순으로 감량(비면 CANCELED)
    → 주문 상태: 전 항목 취소 CANCELED / 일부 cancelPartially (PAID → PARTIAL_CANCELED, SHIPPING 유지)
    → 이력: ORDER 변경 + 비어서 취소된 SHIPMENT.
  - 매핑안됨 항목을 취소해 매핑안됨이 해소되면 매핑 완료로 전이하고 남은 항목을 그때 할당한다.
  - 락 순서: 제품 분산 락 → 주문 행 락(PESSIMISTIC_WRITE). 시딩·소급 할당(제품 락 → 주문 행)과 같은 순서라 교착이 없다.
    제품 락 키는 행 잠금 전에 주문 전체 항목의 전개로 정하고, 잠근 뒤 필요한 제품이 범위를 벗어나면 409(재시도).
    판정은 주문 행 잠금 이후 같은 트랜잭션에서 하므로 판정에 쓴 회차 배정 수량과 실제 감량이 일치한다.
    (출고지시 구현 시 instruct도 같은 주문 행 락을 잡아야 이 정합이 유지된다)
- 상태 변경 이력은 order_status_history 통합 (target_type ORDER/SHIPMENT).

## 6. 엑셀 시딩 (Phase 1)

- OrderSeedColumn enum이 양식·파서·결과 파일의 단일 정의.
- 전 구간 스트리밍 (읽기 SAX, 쓰기 SXSSF). 서식 비보존은 의도된 선택. 10MB 제한.
- 사전 일괄 조회(매핑 이력·사은품 SKU·기존 주문번호) → 검증 전부 선행 →
  통과 주문만 JdbcTemplate 배치 INSERT. JPA saveAll 금지(IDENTITY 배치 무력화).
- 주문 단위 트랜잭션: 한 행 실패 시 그 주문 전 행 실패 표기
  (원인 행에 구체 사유, 나머지는 "동일 주문 내 다른 행 오류").
- 결과는 200 + 결과 파일(처리결과 열) + X-Seed-* 건수 헤더. 행 오류에 4xx 금지.
- 업로드 권한: ADMIN·COMPANY_STAFF·BRAND_STAFF (WORKER 제외, SecurityConfig).
  각 브랜드 관리자가 자기 주문을 올리는 운영 도구다.
- 브랜드 범위 정책: 파라미터 brandId만 받는다 (companyId 없음). brandId가 탐색 범위를 정의하고,
  업로더 스코프는 @ScopeCheck(BRAND)로 검사 (없는 브랜드 404 / 스코프 밖 403 / 계약종료 400).
  - 사전 일괄 조회에 브랜드 조건을 쿼리 레벨로 건다: 매핑은 sale_products.brand_id = :brandId 조인,
    사은품은 products.brand_id = :brandId. "그 브랜드 것만 찾는다"가 쿼리 한 곳에서 보장된다.
  - 범위 밖·미등록·주문일시 구간 밖은 구분하지 않고 동일 사유:
    "선택한 브랜드에 등록된 매핑이 없습니다 (채널=…, 상품코드=…, 옵션코드=…)" (옵션 없음은 "없음").
  - 엑셀 시딩은 단일 브랜드 파일 전용 (멀티브랜드 주문은 수집 API 범위).
- 사은품 행(Y): 상품코드 칸을 내부 SKU로 해석, products 직접 참조, 단가 0만 허용.
- 매핑 없는 일반 상품 행은 오류가 아니다: 주문을 mapping_pending으로 등록하고
  처리결과에 "성공(매핑안됨)" + 사유를 적는다 (X-Seed-Unmapped 건수). 매핑 등록 시 §5 규칙으로 확정.

## 7. 코드 컨벤션

- 엔티티: Long FK 참조로 통일 (@ManyToOne 객체 참조 금지 — LoginHistory만 예외적
  역사로 Long 전환 완료). @Setter 금지, 의미 있는 변경 메서드.
  @NoArgsConstructor(PROTECTED), 정적 팩토리. 엔티티가 다 못 지키는 규칙은
  서비스 레이어 책임이며 해당 엔티티 javadoc에 명시.
- 원장·이력 기록은 반드시 서비스의 단일 경로로만 쓴다 (이형 참조 무결성).
- 커밋은 Conventional Commits.

## 8. 미결 / 확장 메모 (임의 구현 금지)

- 기기 신뢰 인증·TOTP (2차, docs/device-registration.md)
- 이벤트 사은품 자동 부착 규칙 / 주문 수동 사은품 매핑
- 수집 API (매핑안됨 보관 구조는 §5로 확정, 시딩과 공유)
- 채널 재고 밀어내기 (ChannelInventorySync 경합 처리 포함)
- 다창고 분리, 반품 검수, sale_products predecessor 추적
- 취소로 CREATED 회차 배정이 줄 때 shipments.total_amount 재산정 (산정 규칙이 분리 기능과 함께 정해지므로 지금은 갱신하지 않음)
- 마켓 수집발 취소 (수집 API와 함께), 출고 완료 시 physical 차감 (출고 Phase), 등록-할당 정합 검증의 주기 실행
- oms·cbt 출고 연동 (출고 Phase에서 설계 후 구현). 필요해 보이면 만들지 말고 질문으로 올릴 것:
  - cbt 스키마(접수건/작업 테이블) 설계와 마이그레이션
  - 출고 접수 API의 payload 계약, LogisticsClient 인터페이스와 구현체
  - cbt → oms 콜백 계약과 oms 쪽 수신 처리
  - cbt의 작업 화면/API (작업 목록, 스캔 검증, 상태 전이)
  - 접수 실패/재시도, 두 시스템 간 정합성 검증
- **문서-코드 불일치: 멀티브랜드 주문.** §5는 멀티브랜드 주문을 허용하지만, 현재 주문 등록 코드
  (OrderRegistrationCommand의 단일 brandId, OrderRegistrationService)는 "한 주문 = 단일 브랜드"를 전제한다.
  시딩은 단일 브랜드라 영향 없음. 수집 API 구현 시 등록 경로를 항목별 브랜드로 확장할 것.
