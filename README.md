# crossborder

크로스보더 이커머스 주문·출고 관리 시스템 (멀티모듈: common / infra / auth / oms / cbt / batch).

- 로컬 실행·시크릿: 아래 두 절
- 설계 서사 (문제 → 측정 → 해결 과정을 담은 절):
  주문 엑셀 시딩(3-패스 파이프라인, 메모리·성능 측정) /
  주문 시각 기준 상품 매핑(배치 지연이 깨뜨린 설계와 이력화) /
  재고 할당(묶음 락, 멱등 소급) /
  대용량 조회 검증(주문 1,000만 건, 옵티마이저 계획 실측과 측정 방식 교정)
- 구현 규칙 전체: "설계 결정" 절 (§1~§8) — 모든 절의 §N 참조가 이걸 가리킴

## 로컬 실행

필요한 것: JDK 21, Docker

```bash
docker compose up -d          # MariaDB, Redis
./gradlew :oms:bootRun        # http://localhost:8082
./gradlew :auth:bootRun       # http://localhost:8081
./gradlew :cbt:bootRun        # http://localhost:8083
```

oms와 cbt는 별도 DB를 쓴다 (로컬은 같은 MariaDB의 `crossborder_oms` / `crossborder_cbt` 스키마).
Flyway 마이그레이션은 각자 자기 DB만 실행하고, 
auth는 Flyway 없이 validate만 하므로(스키마 소유는 oms), 
빈 DB 최초 기동 시에만 oms 선행 필요. 이후에는 순서 무관

`bootRun`은 `local` 프로필로 실행되며, 로컬 DB 비밀번호와 JWT 서명 키는 각 모듈의 `application-local.yml`에 들어 있다.
이 값들은 로컬 전용 더미 값이라 별도로 전달받거나 환경변수를 설정할 필요가 없다.

## 시크릿 관리

실제 시크릿은 저장소에 커밋하지 않는다. 기본 프로필(`application.yml`)은 환경변수만 참조하고 기본값을 두지 않으므로,
값이 주입되지 않으면 애플리케이션이 기동 단계에서 실패한다.

| 환경변수 | 사용 모듈 | 설명 |
|---|---|---|
| `JWT_SECRET` | auth, oms, cbt | HS256 서명 키 (Base64, 디코딩 후 256bit 이상). 세 모듈이 같은 값을 써야 한다 |
| `DB_PASSWORD` | auth, oms, cbt | DB 비밀번호 |

키 생성 예: `openssl rand -base64 32`

## 주문 엑셀 시딩

**1. 메모리 사용량 축소 — 읽기·쓰기 스트리밍**
- 읽기는 SAX 스트리밍, 결과 파일 쓰기는 SXSSF 스트리밍
- 트레이드오프: 결과 파일의 셀 서식·부가 시트는 보존하지 않는다 (값 + 헤더만).
  서식 보존(XSSF 재로드)은 파일당 수백 MB 힙 피크와 동시 실행 제한이 필요해,
  재업로드 루프에 서식이 불필요하다는 점을 근거로 스트리밍을 선택

**2. 범위 제한 — 브랜드 단위 운영 도구**
- 파일 1개 = 브랜드 1개. 업로드 시 지정한 브랜드의 매핑·제품만 조회하므로(쿼리 레벨 브랜드 조건) 멀티브랜드 주문은 이 경로로 들어오지 않는다 — 수집 API(마켓 연동) 몫

**3. 성능 측정 (2026-10-04)**

5만 행(주문 23,921건)을 재고 할당까지 포함해 업로드부터 결과 파일 응답까지 **약 5.0초**에 처리한다 (초당 약 1만 행).
메모리에 남는 행 데이터는 청크 1개분이라 힙 128MB에서도 같은 파일을 처리한다 (아래 4번).

| 행 수 (주문 수) | 전체 | 인덱스 (1차) | 검증 (2차) | 등록 (+재고 할당) | 결과 파일 (3차) | HTTP 응답 |
|---|---|---|---|---|---|---|
| 10,000 (4,806) | 1,050ms | 120ms | 162ms | 418ms | 346ms | 1.06s |
| 50,000 (23,921) | 4,986ms | 573ms | 727ms | 1,981ms | 1,697ms | 5.01s |

- 단계: 인덱스 = 1차 패스(SAX로 주문키 → 행 번호만 수집) / 검증 = 2차 패스 파싱 + 청크분 매핑 이력·사은품 SKU 일괄 조회 +
  행·주문 검증 / 등록 = 청크별 기존 주문번호 일괄 조회 + JdbcTemplate 배치 INSERT + 재고 할당 (500주문 단위 트랜잭션,
  묶음마다 제품 분산 락 1회) / 결과 파일 = 3차 패스(원본 재독) + SXSSF 쓰기. 업로드를 임시 파일로 받는 시간은 제외
- 수치는 크기별 3회 측정의 중앙값 (단계별로 각각 중앙값). 첫 업로드는 JIT 워밍업용으로 따로 1천 행을 올리고 제외했다.
  1만 행 첫 회는 1,194ms로 이후 회차보다 느렸다.
- 행 검증은 키를 모아 IN 일괄 조회하므로 행 수에 거의 비례하지 않는다. 가장 큰 비용은 DB INSERT(등록)와 xlsx 읽기·쓰기다.
- 재고 할당은 주문마다가 아니라 500주문 묶음마다 락을 잡고 제품별 합산 UPDATE를 해서, 할당 추가 시 등록 단계가 약 170ms만 늘었다
  (1-패스 구조 기준 5만 행 3,278ms → 3,419ms).
- 관련 수치: 소급 할당 32.9만 건 11.3초, 매핑 1건으로 매핑안됨 주문 405건을 확정·할당하는 요청 0.52초.

**4. 측정으로 찾은 병목과 개선** (1차 개선은 위 1번의 읽기·쓰기 스트리밍)

2차 개선 — 파싱 전 압축 해제 (힙 128MB 파싱 OOM 제거, 재고 할당 추가 전 측정)

| 힙 상한 (`-Xmx`) | 1만 행 | 5만 행 (변경 전) | 5만 행 (변경 후) |
|---|---|---|---|
| 1GB | 성공 | 성공 3,179ms | 성공 3,278ms |
| 256MB | 성공 | 성공 3,372ms | 성공 3,599ms (Full GC 0회) |
| 128MB | 성공 | **OutOfMemoryError** (파싱 단계) | 성공 3.8~15.2초, 중앙값 6.0초 (Full GC 507회) |

- 원인: `OPCPackage.open(InputStream)`은 xlsx(zip)의 엔트리를 전부 압축 해제해 메모리에 올린다.
  행 단위 SAX 파싱을 해도 그 이전 단계에서 5만 행 시트 XML 전체가 힙에 올라와 128MB에서 OOM이 났다.
- 변경: 업로드를 임시 파일로 저장하고 `OPCPackage.open(File, READ)`로 열어 필요한 엔트리만 스트림으로 읽는다.
- 남은 병목: 128MB에서 Full GC가 반복돼 시간이 크게 흔들렸다. 전 행 파싱 → 전체 일괄 조회 → 전체 검증 → 등록 구조라
  전체 행 데이터와 등록 명령이 등록 종료까지 살아 있기 때문이다. 재고 할당을 붙인 뒤에는 128MB에서 등록 단계 OOM이 됐다.

3차 개선 — 3-패스 청크 파이프라인 (메모리를 행 수와 무관하게)

| 5만 행, 3회 | 변경 전 (1-패스, 재고 할당 포함) | 변경 후 (3-패스 청크) |
|---|---|---|
| 힙 128MB | **OutOfMemoryError** (등록 단계, 3회 모두) | 성공 5.1~5.4초, Full GC 0~1회 |
| 힙 1GB | 3.43~3.50초 | 4.98~5.02초 |

- 구조: 1차 패스는 주문키 → 행 번호 인덱스(int 배열)만 만든다. 2차 패스는 행을 흘리며 주문이 완성되는 대로 모아
  1,000주문(`crossborder.seed.chunk-orders`)마다 조회·검증·등록·할당하고 청크 데이터를 버린다. 3차 패스는 원본을 다시
  SAX로 읽어 원본 행 순서대로 처리결과 열을 SXSSF로 쓴다. 떨어진 같은 주문 행은 1차 인덱스가 한 주문으로 묶는다.
- 결과 보관도 압축한다: 주문별 결과 1건(종류 + 주문번호·사유)과 실패·매핑안됨 행의 사유만 남기고,
  성공·스킵 행의 문구는 3차 패스에서 주문별 결과로 조립한다.
- 트레이드오프: 힙 1GB 기준 3.4→5.0초(+45%)를 수용하는 대신, 힙 128MB에서 OOM이던 5만 행이 5.1~5.4초·Full GC 0~1회로 안정화.
  파싱 3회분 중 2회가 추가 비용의 대부분(회당 약 570ms)이다 — 인덱스용 1차 패스와 결과 파일용 3차 패스.
  행 수집기의 행당 HashMap·셀 참조 객체 생성을 없애 5.2초에서 5.0초까지 줄였고, 나머지는 XML 파싱 자체 비용이다.
  청크 크기를 5,000으로 늘려도(청크 24개 → 5개) 시간은 같았다.
- 운영 힙을 작게 가져갈수록 이득이 큰 구조다. 1GB에서 1-패스보다 느린 대신, 메모리 상한이 파일 크기가 아니라 청크 크기로 정해진다.

측정 방법
- 환경: Apple M4 Pro (12코어) / 24GB / macOS 26.5.2 / Temurin JDK 21.0.11 (G1 기본 설정) / MariaDB 11.4 (Docker, 같은 장비) /
  oms bootJar를 `-Xms1g -Xmx1g`로 실행. 힙 상한 비교는 `-Xmx`만 바꿔 다시 실행했다 (`-Xlog:gc`로 Full GC 횟수 확인).
- 3차 개선 비교: 변경 전은 직전 커밋을 별도 worktree에서 빌드한 jar로, 변경 후와 같은 순서·조건(워밍업 1천 행 후 3회)으로 쟀다.
- 데이터: `./gradlew :oms:generateSeedFile -Prows=10000 -Pprefix=P10K1` (`OrderSeedFileGenerator`, 난수 시드 고정).
  로컬 테스트브랜드 매핑을 쓰며, 주문당 1~3행, 주문의 5%는 매핑 없는 상품 포함(매핑안됨 등록), 10%는 사은품 행 포함.
  회차마다 채널주문번호 prefix를 바꿔 중복 스킵 없이 전부 신규 등록되게 했다.
- 실행: `scripts/seed-benchmark.sh <파일>` (brand@test.local 로그인 → 업로드 → HTTP 응답 시간·건수 출력).
  단계별 시간은 oms 로그의 `주문 시딩 완료 ... phasesMs={...}` 줄에서 읽는다.
- DB 상태: 측정 회차마다 주문이 누적됐다. 3차 개선 측정 시점 기존 주문은 약 70만~100만 건이다.
  2차 개선 표는 0건에서 약 24만 건 사이에 쟀다.

## 주문 수집: 주문 시각 기준 상품 매핑

**1. 문제 — 수집 시점의 매핑으로는 늦게 수집된 주문의 구성이 틀어진다**
- 마켓 상품코드(예: `AAA`)는 그대로 두고, 재고 사정으로 11시에 구성품을 바꾼다.
  판매상품 구성은 주문 이력이 생기면 바꾸지 않는 불변 규칙이라, 신 구성 판매상품을 만들고 `AAA`의 매핑을 옮긴다
- 주문 수집은 배치라 지연되고, 서버 장애나 매핑 수정 후 재수집이면 몇 시간 전 주문이 지금 수집된다
- 매핑을 "현재 값" 하나로 두면 11시 30분에 수집된 10시 1분 주문이 신 구성에 붙는다.
  주문 시점 구성 스냅샷도 같은 문제다 — 늦게 수집하면 이미 바뀐 구성을 복사한다
- 기준은 수집 시각이 아니라 주문 시각이어야 한다: 10시 1분 주문은 언제 수집되든 10시 1분의 구성으로 나간다

**2. 해결 — 매핑을 유효 기간이 있는 이력으로 전환 (V2 `sale_product_channel_mappings`)**
- `sale_product_channel_mappings`에 `effective_from`(포함) / `effective_to`(미포함, NULL이면 현재 유효)
- 주문 수집은 `ordered_at`이 속한 기간의 매핑을 쓴다. 같은 주문은 몇 번 재수집해도 같은 판매상품에 붙는다
  — 수집 멱등성이 시간 축까지 확장된다
- 매핑 행은 고치지 않는다. 재지정 = 현재 행 마감 + 신규 행 추가, 삭제 = 마감 (그 기간 주문의 재수집에 필요해 행은 남긴다)
- 역할 분리: 구성은 판매상품 단위로 불변이고, 시간 축은 매핑이 담당한다

**3. 설계 결정**
- 반열림 구간 `[from, to)`: 양 끝 포함(`BETWEEN`)이면 전환 시각과 정확히 같은 주문이 구·신 두 행에 동시에 걸린다
- 전환 시각은 항상 처리 시점. 과거로 소급하지 않아 이미 수집된 주문을 다시 옮길 일이 생기지 않는다
- 최초 매핑은 기간 시작 없음(`1000-01-01`)부터 유효: 매핑 등록 전에 들어온 매핑안됨 주문도 등록 즉시 확정된다
- 현재 유효 행은 키당 1개를 DB가 보장: `effective_to IS NULL`일 때만 1인 생성 컬럼에 유니크를 건다.
  기간 겹침은 DB 제약으로 표현할 수 없어 마감·신규를 한 트랜잭션에서 행 잠금으로 처리하고, 유니크가 최종 방어선이다
- 마감 후 재개는 `max(처리 시각, 최종 마감 시각)`부터: 서버 간 시계가 어긋나 마감 시각이 처리 시각보다 늦어도 기간이 겹치지 않는다
- 일괄 조회 구조는 유지: 코드별 이력을 한 번에 IN 조회하고 주문 시각으로 메모리에서 매칭한다 (조회 횟수 동일)

**4. 검증 — `ChannelMappingHistoryTest` (Testcontainers MariaDB 11.4, 처리 시각은 고정 시계)**

| 시나리오 | 검증 |
|---|---|
| 재지정 원자성 | 구 행 `effective_to` = 신 행 `effective_from` = 재지정 시각, 현재 유효 행 1개 |
| 경계 시각 매칭 | 전환 1초 전 → 구 구성 / 전환 시각 정각 → 신 구성 / 1초 후 → 신 구성, 각 시각을 덮는 행은 정확히 1개 |
| 동시 재지정 | 같은 매핑에 10건 동시 요청 → 1건 성공, 9건 409, 현재 유효 행 1개 |
| 같은 초 재지정 | 두 번째는 409 (빈 구간 `[T, T)` 거부), 이력 변화 없음 |
| 재개 시 겹침 방지 | 처리 시각이 최종 마감보다 이르면 최종 마감 시각부터 재개, 경계 전후 시각을 덮는 행은 정확히 1개 |

전제: 처리 시각(서버 시계)과 마켓 주문 시각이 같은 시간대(UTC+9)다. 다른 시간대 마켓이 붙으면 주문 시각 변환이 필요하다.

## 재고 할당

**1. 할당 시점 — `orders.allocated_at` = 주문 전체 할당 완료 시각**
- 등록(시딩): 500주문 묶음 트랜잭션에 INSERT와 할당을 함께 넣는다. 등록됐는데 미할당인 주문은 남지 않는다
  (락 타임아웃 등으로 실패한 주문은 INSERT까지 롤백되고 결과 파일에 사유가 남는다)
- 매핑안됨 주문: 항목 매핑이 확정될 때마다 그 주문의 항목이 모두 확정됐는지 보고, 모두 확정이면 즉시 주문 전체를 할당한다
- 취소: 취소 수량의 전개분만큼 할당을 해제한다. 매핑안됨 항목을 취소해 매핑안됨이 해소되면 남은 항목을 그때 할당한다.
  전 항목이 취소된 주문은 할당하지 않고 `allocated_at`도 기록하지 않는다 (소급 대상에서도 제외)

**2. 소급(backfill) — 관리자 수동 API `POST /api/admin/allocations/backfill`**

정상 흐름에서 빠진 예외 상황과 `allocated_at` 도입 이전 데이터 정리용이다.

- 조회 대상: `allocated_at IS NULL AND status != 'CANCELED'`
- DELIVERED 주문은 조회될 수 있으나, 재고 정합성 문제로 인해 할당을 수행하지 않는다.
  발견 시 이상 데이터(`anomalies`, `anomalyOrderIds`)로 집계하여 운영자가 확인한다
- 매핑안됨 주문은 건너뛴다 (`skipped` — 매핑 완료 시 자동 할당)
- 멱등: 주문 행을 잠근 뒤 `allocated_at IS NULL`을 다시 확인하고 같은 트랜잭션에서 시각을 기록한다
- 응답: `{processed, allocated, skipped, failed, anomalies, anomalyOrderIds}` (processed = allocated + skipped + failed + anomalies)

## 대용량 조회 검증 (주문 목록 1,000만 건)

주문이 1,000만 건 쌓였을 때 주문 목록 API가 버티는지를 실측하고, 그 결과로 쿼리 구조·인덱스(V8)·검색 스펙을 정했다.
**모든 수치는 InnoDB 버퍼풀 128MB 조건이다** (MariaDB 기본값, 주문 테이블 4.9GB의 약 3%). 큰 스캔이 디스크 읽기에
지배되는 최악 조건이라, 운영(버퍼풀을 데이터에 맞춰 키운 서버)에서는 느린 수치가 더 작아진다.

**1. 실험 설계 — 실험 스키마 `explain_lab`**
- 운영 스키마를 복제한 별도 DB에 분포를 만든 데이터를 넣었다 (운영 crossborder 스키마는 건드리지 않음).
  - orders 1,007만: 회사 200 · 브랜드 1,000 · 2년 균등 분포. 상태는 DELIVERED 80% / PAID 8% / SHIPPING·CANCELED 각 5% / PARTIAL_CANCELED 2%
  - order_items 2,014만: 주문당 1~3행, 항목 브랜드 = 주문 브랜드. 판매상품은 브랜드당 20개(총 2만, 제품과 1:1)에 해시로 분산 → SKU당 항목 약 1,000
  - shipments 1,068만: 주문 상태에서 유도 (출고된 주문은 MASTER_SHIPPED, PAID의 40%는 미분리, 나머지 PAID는 CREATED~PALLETIZED,
    CANCELED의 30%는 CANCELED 회차만)
- 비교 방법: 후보 인덱스를 모두 만든 뒤 "전"은 `IGNORE INDEX`로 재서, 같은 데이터·같은 캐시 조건에서 전/후를 비교했다.
- 측정: 같은 세션에서 쿼리 전후 `NOW(6)` 차이(서버 경과 시간). 5초 미만은 3회 중앙값, 120초 타임아웃.
  1페이지 = 20행, 건수 = 상한 10,001까지 세는 쿼리.
- 환경: 엑셀 시딩 절과 같은 장비 (Apple M4 Pro, MariaDB 11.4 Docker).

**2. 발견 1 — 옵티마이저가 3행짜리 채널 테이블부터 읽는다**

기존 목록 쿼리는 판매채널 코드를 가져오려고 `sales_channels`(3행)를 조인했다. 옵티마이저는 작은 테이블이 앞에 오는 순서를 골라
채널마다 `uk_orders_channel_order`로 주문 전부를 읽고, 임시 테이블에 모아 정렬했다. 정렬 전 모든 행에서 항목 수 서브쿼리까지 돌아
30일 조건 1페이지가 100초를 넘었다. 인덱스를 추가해도 이 순서를 버리지 않는다.

| 1페이지 | 현재 코드 | 채널 조인 제거 (현행 인덱스) | + 신규 인덱스 |
|---|---|---|---|
| ADMIN 30일 | 104.3초 | 14.7초 | 5.8ms |
| ADMIN 365일 | — (기간 없음은 120초 초과) | 14.2초 | 0.7ms |
| COMPANY 30일 | 96.4초 | 6.9초 | 3.0ms |
| COMPANY 365일 | — | 3.9초 | 1.0ms |
| ADMIN 365일 + 상태 IN | — | 15.2초 | 27ms |
| ADMIN 365일 + 채널 | — | 39.6초 | 26ms |

- 대책: 채널을 조인하지 않고 코드는 앱 메모리 캐시로 붙인다 (`SalesChannelCodes`, 채널 코드는 불변이라 무효화 없음).
  `STRAIGHT_JOIN`으로 순서를 고정해도 같은 효과지만 JPQL(QueryDSL)로 쓸 수 없다.
- 정확한 총건수(`count(*)`)는 ADMIN 30일 5.6초. 상한 10,001까지만 세면 30일 2.7ms, 365일 1.4ms.

**3. 발견 2 — 세미조인 변환이 드라이빙 순서를 뒤집는다**

배송상태 필터를 `EXISTS (회차 상태 IN …)`로 쓰면, 30일 범위에서는 주문을 최신순으로 읽으며 회차를 확인하는 계획(FirstMatch)이라
빠르다. 그런데 365일 범위에서는 MariaDB가 EXISTS를 IN으로 바꾼 뒤 materialization을 골라 **shipments 인덱스 1,060만 행 전체를
먼저 읽어 임시 테이블을 만든다**. 같은 쿼리가 기간 길이에 따라 다른 계획을 탄다.

| 배송상태 365일 | EXISTS (현행 인덱스) | EXISTS (+신규 인덱스) | 상관 count 형태 (+신규 인덱스) |
|---|---|---|---|
| CREATED 1페이지 | 7.7초 | 913ms | 7.9ms |
| MASTER_SHIPPED 1페이지 | 15.5초 | 9.2초 | 0.7ms |
| 미분리 1페이지 | 18.0초 | 9.9초 | 3.0ms |
| CREATED 상한 건수 | 3.1초 | 1.13초 | 1.15초 |
| 미분리 상한 건수 | 11.4초 | 10.2초 | 255ms |

- 대책: `(SELECT count(*) … ) > 0` / `= 0` 형태의 상관 서브쿼리는 세미조인 변환 대상이 아니라 드라이빙 테이블이 주문으로 고정된다.
  JPQL로 쓸 수 있는 형태이기도 하다. 의미가 EXISTS / NOT EXISTS와 같다는 것은 믿지 않고 테스트로 묶었다 —
  회차 없음 / CANCELED 회차만 / CANCELED + CREATED(재분리) / 여러 상태 회차를 EXISTS 원형 SQL과 비교한다 (`OrderSearchTest`).
- 미분리 정의: 회차가 없는 주문이 아니라 **유효 회차(CANCELED 아님)가 없는 주문**. 재분리는 기존 회차를 CANCELED로 남기고 다시 나누므로
  CANCELED 회차만 남은 주문은 다시 나눠야 하는 주문이다.
- `shipments(order_id, status)` 단독 효과는 약 2배다 (CREATED 365일 1페이지 30ms → 7.9ms, 건수 2.4초 → 1.15초). 회차 상태 판정을
  인덱스만으로 끝낸다.

같은 현상이 브랜드·SKU 조건에도 있었다. 다만 여기서는 "항상 주문에서 출발"이 정답이 아니었다.

- 브랜드 (`EXISTS (항목 브랜드 = ?)`): 옵티마이저가 브랜드 항목에서 출발해 주문을 붙이고 정렬하는 계획을 골라 1~2.6초.
  (order_id, brand_id) 복합 인덱스를 넣어도 계획이 같았다. 상관 count 프로브로 바꾸면 목록이 빨라지지만, 건수는 일치가 상한보다
  적을 때 기간 전체를 훑어 오히려 느려진다. 그래서 **목록은 프로브, 건수는 EXISTS**로 형태를 나눴다.

  | ADMIN + 브랜드 (주문의 0.1%) | EXISTS | 상관 count + (order_id, brand_id) | order_brands 반정규화 (미채택) |
  |---|---|---|---|
  | 30일 1페이지 / 건수 | 1.8초 / 2.3초 | 23ms / 459ms | 24ms / 8.6ms |
  | 365일 1페이지 / 건수 | 2.6초 / **1.5초** | **22ms** / 8.3초 | 0.8ms / 361ms |

- SKU: 해석된 제품 수(선택도)에 따라 정반대 형태가 빠르다. 항목에서 출발하는 IN 세미조인은 읽는 항목 수에 비례하고,
  주문에서 출발하는 프로브는 페이지가 찰 때까지 읽는 주문 수에 비례한다.

  | SKU 30일 (1페이지 / 상한 건수) | IN 세미조인 (항목 출발) | 상관 count 프로브 (주문 출발) |
  |---|---|---|
  | 1제품 (항목 약 1,000) | **2.8ms / 2.0ms** | 264ms / 300ms |
  | 20제품 (약 2만) | 553ms / 565ms | **63ms** / 1.3초 |
  | 200제품 (약 20만) | 5.2초 / 5.8초 | **20ms / 1.2초** |
  | 1,000제품 (약 100만) | 33.5초 / 1.3초 | **3.2ms / 323ms** |

  대책: SKU → 제품 → 판매상품을 먼저 해석하고, 해당 항목 수를 5,001까지만 센다 (`(sale_product_id, order_id)` 인덱스로 약 1ms).
  5,000 이하면 IN 세미조인, 넘으면 프로브 (`crossborder.order-search.sku-sparse-item-threshold`).
  5,000은 IN 경로가 1,000항목 2~6ms · 2만 항목 550~940ms 사이에서 100~200ms 수준이 되는 근사값이다.
  부분 일치는 365일이면 프로브 건수가 최대 21초라 기간을 31일로 제한한다.

**4. 발견 3 — 양쪽 와일드카드 LIKE는 인덱스와 무관하게 범위 전체를 읽는다**

수취인명 `LIKE '%…%'`(일치 0건)는 기간 없이 19.7초, 365일 9.3초, 30일 548ms. 운영 요구가 없어 검색 스펙에서 수취인·주문자·상품명을 뺐다.
남긴 부분 일치(주문번호 단건)도 같은 성질이라 1페이지는 빨리 차도(약 4ms) 건수가 기간에 비례한다 (7일 96ms / 30일 556ms / 365일 8.4초)
→ 부분 일치는 최대 31일.

**5. 측정 오류와 교정**

첫 측정은 MariaDB `information_schema.profiling`의 단계별 시간을 합산했다. 결과 중 **지연 조인이 100만 행을 읽고 0.2ms**라는 값이
나왔다 — 100만 건 인덱스를 읽는 데 0.2ms는 물리적으로 불가능하다. 같은 쿼리를 벽시계로 다시 재 보니 현재 코드 1페이지는
기록상 1.06초가 실제 41초, 지연 조인 0.2ms는 실제 약 110ms였다.

- 원인: profiling은 쿼리당 마지막 약 100단계만 보관한다. 목록 쿼리는 행마다 항목 수 서브쿼리가 돌아 단계가 수백 개 생기고,
  비싼 앞단계(전체 스캔·filesort·파생 테이블)가 잘려 합계가 과소 집계됐다. 서브쿼리 없는 count 쿼리는 영향이 작았다.
- 조치: 첫 측정 75건을 전량 폐기하고, 같은 세션의 `NOW(6)` 차이(단계 단위가 아닌 문장 경계)로 다시 쟀다. 위 수치는 모두 재측정값이다.
- 같은 재측정에서 데이터 결함 두 가지도 고쳤다: 항목이 주문의 10%에만 있어 브랜드 비교가 불공정했던 것(전 주문분으로 재생성),
  판매상품 배정식이 브랜드 배정(주문 ID 기준)과 맞물려 브랜드당 3종에만 몰렸던 것(해시 분산으로 교체 — 정확 일치 SKU가 0건으로
  나와 발견).
- 교훈: 측정 도구의 수치도 물리적으로 말이 되는지 먼저 의심하고, 다른 방법으로 교차 검증한다.

**6. 채택한 구조**

| 항목 | 결정 | 근거 수치 |
|---|---|---|
| 페이지 | 지연 조인: 조건·정렬·offset은 주문 ID만으로(인덱스), 표시 컬럼·항목 수는 그 페이지 ID에만 | offset 10만: 그대로 103ms → 12ms (COMPANY offset 1만: 320ms → 2.1ms) |
| offset 상한 | 100,000 초과는 400 (조건을 좁혀 재조회 안내) | 화면은 앞쪽 페이지 + 조건 재조회 패턴 |
| 페이지 크기 | 기본 20, 최대 1,000 (초과 400) | 1,000행: 기본 9.5ms / 배송상태 324ms / SKU 부분 71ms / 브랜드 3.1초 |
| 건수 | 상한 집계 — 10,000건까지 세고 넘으면 "10,000+" (`totalCapped`). 마지막 페이지는 건수 쿼리 생략 | 정확 count 5.6초 → 2.7ms |
| 기간 | 항상 적용. 미지정이면 최근 30일, 최대 366일. 부분 일치는 최대 31일 | 표 2·4 |
| 주문번호 복수 | 정확 일치 배열 최대 500개, `orders(channel_order_no)` | 100개 5.8초 → 1.5ms, 500개 5.9초 → 3.6ms |

- 브랜드 1,000행 페이지(3.1초)는 주문의 0.1%인 브랜드에서 1,000건을 채우려 약 100만 주문을 확인하기 때문이다. order_brands를 쓰지 않는
  대가로 남긴 최악값이다 (아래 7번).
- 커서(ordered_at, id) 순차 추출은 5만 행에 0.43~0.69초(쿼리만) — 엑셀 다운로드의 조회 방식이다 (아래 8번).

V8 인덱스 (각 인덱스가 지탱하는 쿼리는 마이그레이션 주석에 명시):
`orders(ordered_at, id)`, `orders(company_id, ordered_at, id)`, `orders(channel_order_no)`, `order_items(order_id, brand_id)`,
`order_items(sale_product_id, order_id)`, `shipments(order_id, status)`. FK가 자동으로 만든 인덱스 3개(`fk_orders_company`,
`fk_order_items_order`, `fk_order_items_sale_product`)는 같은 컬럼이 선두인 새 인덱스가 생기면 InnoDB가 스스로 지운다 —
FK 인덱스를 늘리지 않고 대체한다. 모두 `ALGORITHM=INPLACE, LOCK=NONE` (운영 쓰기를 막지 않음).

구현 확인: Hibernate가 실제로 만든 SQL(테스트 로그)을 같은 실험 데이터에서 다시 돌렸다.

| Hibernate SQL (365일, 1페이지 / 상한 건수) | 시간 |
|---|---|
| ADMIN | 0.3ms / 2.2ms |
| COMPANY | 0.3ms / — |
| 브랜드 (목록 프로브 / 건수 EXISTS) | 11.3ms / 575ms |
| 배송상태 CREATED | 3.8ms / 1.08초 |
| 미분리 | 1.3ms / 153ms |
| SKU 1제품 (희소, IN) | 7.5ms / 3.8ms |
| SKU 1,000제품 30일 (밀집, 프로브) | 4.4ms / 289ms |

**7. 채택하지 않은 것**
- `order_brands(brand_id, ordered_at, order_id)` 반정규화: 브랜드 365일 1페이지 0.8ms · 건수 361ms로 가장 빨랐지만(복합 인덱스 대비 20~50배),
  주문 등록·항목 변경 모든 경로에 동기화 쓰기가 영구히 붙는다. 테이블 신설 없이 인덱스로 받치는 쪽(목록 22ms)을 택했다.
- 커서 페이지네이션(화면): 페이지 번호 UI를 유지하고 지연 조인으로 충분하다. 커서는 다운로드 전용.
- 주문 상태 인덱스: COMPANY의 결과 0건 조건(365일 상태·매핑안됨)이 1.1~1.7초지만 회사 규모(최대 5만 건)로 상한이 정해져 넣지 않았다.

**8. 주문 다운로드 측정 (쿼리 + 엑셀 생성 합산)**

| 데이터 행 (주문) | HTTP 응답 | 생성 (조회+전개+SXSSF 행 쓰기) | xlsx 쓰기 | 파일 | 힙 1GB | 힙 128MB |
|---|---|---|---|---|---|---|
| 57,779 (18,113) | 5.2~5.5초 | 3.7~3.9초 | 0.38초 | 4.8MB | 성공 | 성공, Full GC 0회 |
| 97,434 (37,022) | 6.4~6.9초 | 5.8~6.2초 | 0.64초 | 8.3MB | 성공 | 성공, Full GC 0회 |

- 메모리: 힙 128MB 측정 내내 GC 후 힙이 46~71MB로 행 수와 무관했다 (애플리케이션 기본 사용량 포함). 메모리에 남는 것은
  1,000주문 묶음 1개와 SXSSF 쓰기 창뿐이고, 최종 xlsx도 응답 스트림에 바로 쓴다.
- 시간의 대부분은 묶음마다 항목을 읽는 조회다 (1,000주문 묶음당 약 120ms, 버퍼풀 128MB라 항목 행 랜덤 읽기). ADMIN과
  BRAND_STAFF(스코프 프로브)의 생성 시간이 같았다 (3.6~3.7초 / 3.7~3.9초).
- 측정 조건: 이 절의 다른 수치와 달리 로컬 crossborder DB (주문 약 100만 건, 하루 약 3.7만 건, 항목 210만 건, 버퍼풀 128MB)에서
  bootJar로 쟀다. 하루치 + 채널 1개 = 5.8만 행, 하루치 전체 = 9.7만 행. 워밍업 1회 후 3회. 환경은 엑셀 시딩 절과 같다 (JDK 21, `-Xms/-Xmx`만 변경).
- 실행: `scripts/download-benchmark.sh 'orderedFrom=2026-09-29&orderedTo=2026-09-29&salesChannelId=1'`
  (로그인 → estimate → 다운로드, 단계 시간은 oms 로그 `주문 다운로드 완료 ... generateMs=.. writeMs=..`).

**9. 남은 한계**
- 실험 데이터는 브랜드·SKU가 균등 분포다. 큰 브랜드(주문 비중 큼)는 브랜드 프로브가 더 빨라지고 건수 EXISTS가 느려지는 방향이며,
  항목이 5,000을 넘는 인기 SKU의 정확 일치를 1년 범위로 찾는 경우는 재지 못했다.
- 수치는 128MB 버퍼풀 기준이다. 버퍼풀을 키우면 초 단위 수치(건수, 브랜드 1,000행)는 디스크 읽기가 줄어 작아진다.

## 설계 결정

구현 시 반드시 따라야 하는 확정 결정이다. 여기 없는 설계 판단이 필요하면 임의로 정하지 말고 질문으로 올린다.
본문과 코드 주석의 §N은 이 절의 N번 항목을 가리킨다.

### §1. 모듈 구조

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

### §2. 스키마 / 마이그레이션

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

### §3. 조직 / 인증

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

### §4. 상품 / 재고

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
- 재고 할당 (allocated_stock). 할당 시점·소급 정책은 위 "재고 할당" 절이 기준이고, 여기는 구현 규칙만 적는다:
  - 전개: SALE_PRODUCT 항목 = 판매상품 구성 × 주문수량 (구성 고정 사은품 is_gift 포함), GIFT_PRODUCT = 제품 직접.
    유효(ORDERED) 항목만. 판매가능 음수 허용 — 한도 검사 없이 증가 (physical은 음수 금지 유지).
  - 증감은 원자적 UPDATE(allocated_stock ± ?)로, 제품 락(stock:product:{productId}) 아래 트랜잭션 안에서 한다.
    락은 커밋 후 해제 (executeWithLocks로 트랜잭션을 감싸거나, 열린 트랜잭션이면 lockUntilTransactionEnd).
    할당·매핑 완료·소급·취소가 모두 같은 키 공간(StockAllocator.lockKeys)을 쓴다.
  - orders.allocated_at (V6)은 allocated_at IS NULL인 주문에만 기록하고 같은 트랜잭션에서 할당한다 → 멱등.
    항목 일부만 할당된 주문 상태는 없다.
  - 묶음 실행 규칙은 ChunkedAllocationExecutor 하나로 등록(시딩)과 소급이 공유한다: 500주문 묶음 → 제품 합산 정렬 멀티락 1회
    → 새 트랜잭션 → 커밋 후 해제, 묶음이 락 타임아웃·DB 오류로 실패하면 주문 단위로 재시도해 원인 주문만 실패.
    (주문마다 락·커밋하면 대량 시딩 왕복이 주문 수만큼 늘어나 묶음 단위로 결정)
  - 매핑 확정 = 할당 트리거: OrderMappingService가 항목 매핑을 확정할 때마다 그 주문의 유효 항목이 모두 확정됐는지 본다.
  - 소급·정합 검증 API는 ADMIN 전용(/api/admin/allocations), 동기 실행, 주기 실행은 하지 않는다.
    consistency: 할당 완료 주문의 유효 항목 전개 합 = allocated_stock 비교 + 미할당·매핑안됨 주문 수.
    정합 기대값은 출고 차감이 없는 현재 기준 — 출고 Phase에서 출고분을 빼도록 바꾼다.
- 통관 (일본 화장품 개인 수입 기준):
  - 규정: 1품목당 표준 사이즈 24개 이내, 소용량(60g·60ml 이하) 120개 이내.
    섞여 있으면 소용량 개수를 표준으로 환산하는 식 ((24 − 표준 개수) × 5 = 허용 소용량 개수)이 쓰이며, 참고로만 둔다.
    출처: 일본 세관 Customs Answer 1806 「医薬品・化粧品等の個人輸入について」, 후생노동성(관동신에츠 후생국) 의약품 등 수입 안내.
  - 자동 분할(확실한 규칙): customs_categories 분류 단위 환산 한도. 분류별 Σ(전개 수량 × products.customs_unit_qty)
    > qty_limit 이면 회차 분할 (시트마스크 120매가 대표, 10매입 박스 customs_unit_qty = 10). split_reason = CUSTOMS_LIMIT.
  - 경고만(불확실한 규칙): "품목당 24개"의 품목 판별이 데이터로 불가하다 (샴푸·린스 구분 수준의 분류 체계 없음).
    그래서 회차 전체 개수(판매상품 단위, 환산 없음) > crossborder.customs.quantity-warn-threshold(기본 24)면
    경고만 내고 분할하지 않는다 — 품목 판별 불가로 전체 개수 경고로 근사한다. 경고는 분리 응답·회차 조회에서 계산값으로만
    주고 저장하지 않는다 (기준이 바뀌어도 재계산 불요, 분리 시점 판단 보조). QUANTITY_LIMIT는 현재 미사용.

### §5. 주문 / 출고

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
- 분리 (POST /api/orders/{orderId}/splits, 일괄 POST /api/orders/splits — orderIds 명시, 주문별 독립 트랜잭션·성공/실패 요약):
  - 대상: 매핑 완료 + PAID·PARTIAL_CANCELED + 유효 항목 1개 이상. 분리 시점에 판매상품 구성으로 제품 단위 전개 (지연 전개).
  - 규칙 (ShipmentPlanner): 브랜드별 그룹 → 그룹마다 분류 한도를 넘지 않게 항목을 수량 단위로 항목 id 순 순차 배분
    (넘치면 새 회차, bin-packing 최적화 안 함). 분할 단위는 주문 항목 1개 — 세트의 구성품은 회차별로 쪼갤 수 없다.
    항목 1개의 환산수량이 이미 분류 한도를 넘으면 분리 실패 ("단일 상품이 통관 한도 초과: {sku}, 환산수량 N > 한도 M").
    사은품(건별·구성 고정)도 통관 수량에 포함.
  - split_reason (브랜드 그룹 단위): 분류 한도로 나뉜 그룹 CUSTOMS_LIMIT > 그 외 멀티브랜드 BRAND_SPLIT > 단일 회차 null.
  - 스코프: 주문 스코프 + 유효 항목이 전부 사용자 브랜드 범위 (멀티브랜드 주문은 BRAND_STAFF가 분리할 수 없다).
  - 회차 금액(total_amount) = Σ(배정 항목 unit_price 스냅샷 × 배정 수량). paid_amount를 비례 배분하지 않는다 —
    단가 스냅샷 기반이 통관 신고 참조값으로 설명 가능하고 할인 구조와 무관하게 결정적이다. 참조값이며 주문 금액과의
    합계 일치를 강제하지 않는다. 사은품은 0 기여. 취소로 CREATED 회차 배정이 줄면 같은 규칙으로 재계산.
  - 재분리: CREATED 회차만 있으면 기존 CREATED 회차를 전부 CANCELED 처리하고 다시 분리 (부분취소 후 재분리가 주 용도).
    INSTRUCTED 이상이 하나라도 있으면 거부. 회차 번호는 취소된 회차 포함 최대 번호 다음부터 (shipment_no 유니크).
  - 분리 결과 조회: GET /api/orders/{orderId}/shipments (취소 회차 포함, 경고 계산).
- 출고지시 (POST /api/shipments/{shipmentId}/instruct, 일괄 POST /api/shipments/instruct — 회차 단위):
  - CREATED → INSTRUCTED, instructed_by/at (Clock). 첫 지시 시점에 주문 SHIPPING. ORDER·SHIPMENT 상태 이력.
  - 스코프: 회차 브랜드 기준 (ScopePolicy SHIPMENT). cbt 출고 접수 호출은 하지 않는다 (출고 연동 Phase).
  - 주문 행 락으로 취소 판정과 직렬화한다. 지시는 재고를 바꾸지 않아 주문 행 락만 잡는다 (전 경로 순서 규칙: 제품 락 → 주문 행 락).
    순서를 강제한 테스트로 검증: 취소 커밋 전에 들어온 지시는 커밋을 기다렸다가 거부되고, 그 반대도 같다.
  - 지시 이후 회차의 항목·수량·금액은 바뀌지 않는다 (취소는 INSTRUCTED 배정분을 제외하고, 재분리는 거부).
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
    (출고지시도 같은 주문 행 락을 잡아 이 정합이 유지된다 — 아래 출고지시 참고)
- 상태 변경 이력은 order_status_history 통합 (target_type ORDER/SHIPMENT).
- 주문 목록 검색 (GET /api/orders, 같은 조건을 본문으로 받는 POST /api/orders/search — 주문번호 복수 붙여넣기는 쿼리 문자열이
  요청 헤더 한도 8KB를 넘으므로 POST). 값은 설정 `crossborder.order-search.*`, 근거는 "대용량 조회 검증" 절.
  - 기간(ordered_at)은 항상 적용되는 기본 축: 미지정이면 최근 30일, 최대 366일. 부분 일치(주문번호·SKU)는 최대 31일. 위반 400.
  - 조건(모두 AND): 주문 상태 다중 / 매핑안됨 / 채널 / 브랜드(항목 브랜드 기준, 스코프 안에서 선택 — BRAND_STAFF는 무시) /
    배송상태(회차 중 하나라도 해당 상태) · 미분리(유효 회차 = CANCELED 아닌 회차가 없음, 배송상태와 함께 주면 OR) /
    내부 주문번호 정확 일치 / 마켓 주문번호 복수 정확 일치(최대 500개) · 단건 부분 일치 /
    SKU 정확·부분 일치(판매상품 구성 또는 건별 사은품으로 그 제품이 들어간 주문, 취소된 항목 포함).
  - 지원하지 않음: 수취인명·주문자명·상품명 (운영 요구 없음. 양쪽 와일드카드 LIKE는 기간 전체를 읽는다).
  - 페이지: 크기 기본 20·최대 1,000, offset 최대 100,000 (초과 400, 조건을 좁혀 재조회). 정렬은 주문일시 최신순 고정.
  - 건수: 10,000까지만 센다 (넘으면 totalCapped = "10,000+"). 정확한 총건수는 제공하지 않는다.
  - 쿼리 형태 규칙 (OrderQueryRepository): 판매채널 조인 금지(코드는 앱 캐시) / 지연 조인(ID 페이지 → 표시 컬럼) /
    항목·회차 조건은 상관 count 서브쿼리 형태 — EXISTS는 브랜드 건수에만 (목록과 건수의 빠른 형태가 다르다) /
    SKU는 해석된 항목 수 5,000 이하면 항목에서 출발(IN), 넘으면 주문에서 출발(프로브).
    EXISTS로 되돌리지 말 것: MariaDB 세미조인 변환이 기간 길이에 따라 드라이빙 순서를 바꾼다.
- 주문 목록 엑셀 다운로드 (GET/POST /api/orders/download, 건수 사전 확인 GET/POST /api/orders/download/estimate):
  자체 CBT를 쓰지 않는 고객사가 타사 물류 시스템에 출고지시를 올리는 운영 경로. 값은 설정 `crossborder.order-download.*`.
  - 조건은 주문 목록과 같다 (OrderSearchCriteriaFactory·같은 조건식 — 같은 조건이면 같은 주문 집합, OrderDownloadTest가 비교).
    스코프도 목록과 같다. 페이징 없이 전체 추출.
  - 기간 필수, 최대 31일 (누락·초과 400). 주문 수 상한 10만 건 — 넘으면 생성 전에 400 (조건을 좁혀 나눠 받기).
    한 주문이 여러 행으로 전개되므로 엑셀 시트 한도(1,048,576행)를 지키기 위한 상한이고, 행 한도도 따로 검사한다.
  - estimate: 같은 조건의 주문 수를 상한+1까지만 센다 (`exceedsLimit`). 화면의 기간 경고·소요 시간 안내 재료.
  - 행 = 제품 전개 1행: 판매상품 항목은 구성 제품마다 1행(제품수량 = 항목수량 × 구성수량, 구성 사은품은 사은품출처 COMPOSITION),
    건별 사은품 항목은 그 제품 1행(ORDER), 매핑안됨 항목은 전개 없이 1행(SKU 비움, 채널상품코드로 식별).
    주문·항목 열은 행마다 반복. 취소된 항목은 출고 대상이 아니라 넣지 않는다 (전 항목 취소 주문은 행이 없다).
    회차 열은 두지 않는다 — 출고 관점은 출고지시 리스트의 역할.
  - 열: OrderDownloadColumn이 단일 정의 (시딩 양식 OrderSeedColumn과 별개). 요청 columns 배열(키, 순서 유지, 비면 전체).
    주문번호·채널은 항상 포함 — 요청에 없으면 맨 앞에 붙인다. 모르는 키는 400.
  - 파일명 orders-{시작}-{끝}-{타임스탬프}.xlsx, 응답 헤더 X-Download-Count = 데이터 행 수.
  - 구현: 커서(ordered_at, id)로 1,000주문씩 읽고 그 묶음의 항목을 전개해 SXSSF로 쓴다. 메모리에는 묶음 1개와 SXSSF 쓰기 창(200행)만.
    응답은 동기 스트리밍 — xlsx(zip)는 행을 다 쓴 뒤 한 번에 만들어지므로 생성이 끝나야 바이트가 나간다.
    그 전 오류는 응답이 커밋되지 않아 4xx/5xx로 돌려준다.
  - 스냅샷 일관성은 보장하지 않는다: 묶음마다 따로 조회하므로 다운로드 중 들어오거나 바뀐 주문은 위치에 따라 포함되거나 빠질 수 있다.
  - 동시 실행: 같은 사용자 1건 (Redis 락 `order-download:user:{id}`, 기다리지 않고 409). 시딩에는 동시 실행 제한이 없어
    새로 만들었다. 임대 시간 없이 보유 중 자동 연장(watchdog) — 다운로드 시간을 예측할 수 없어서다 (DistributedLockManager.executeIfAvailable).

### §6. 엑셀 시딩 (Phase 1)

- OrderSeedColumn enum이 양식·파서·결과 파일의 단일 정의.
- 전 구간 스트리밍 (읽기 SAX, 쓰기 SXSSF). 서식 비보존은 의도된 선택. 10MB 제한.
- 3-패스 청크 파이프라인: 1차 주문 인덱스(행 데이터 비보관) → 2차 청크(기본 1,000주문, crossborder.seed.chunk-orders)마다
  청크분 일괄 조회(매핑 이력·사은품 SKU·기존 주문번호) → 검증 전부 선행 → 통과 주문만 JdbcTemplate 배치 INSERT+할당
  → 청크 데이터 해제 → 3차 원본 재독으로 결과 파일 작성. JPA saveAll 금지(IDENTITY 배치 무력화).
  캐시는 청크 수명(범청크 캐시 없음), 청크는 순차 처리. 결과 열은 원본 행 위치에 기록한다.
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

### §7. 코드 컨벤션

- 엔티티: Long FK 참조로 통일 (@ManyToOne 객체 참조 금지 — LoginHistory만 예외적
  역사로 Long 전환 완료). @Setter 금지, 의미 있는 변경 메서드.
  @NoArgsConstructor(PROTECTED), 정적 팩토리. 엔티티가 다 못 지키는 규칙은
  서비스 레이어 책임이며 해당 엔티티 javadoc에 명시.
- 원장·이력 기록은 반드시 서비스의 단일 경로로만 쓴다 (이형 참조 무결성).
- 커밋은 Conventional Commits.

### §8. 미결 / 확장 메모 (임의 구현 금지)

- 기기 신뢰 인증·TOTP (2차, docs/device-registration.md)
- 이벤트 사은품 자동 부착 규칙 / 주문 수동 사은품 매핑
- 수집 API (매핑안됨 보관 구조는 §5로 확정, 시딩과 공유)
- 채널 재고 밀어내기 (ChannelInventorySync 경합 처리 포함)
- 다창고 분리, 반품 검수, sale_products predecessor 추적
- 품목 단위 통관 한도 분할 ("품목당 24개"): 품목 분류 체계가 생기면 SplitReason.QUANTITY_LIMIT로 도입. 지금은 개수 경고만
- 분리 미리보기(dry-run) API: 화면을 붙일 때 필요하면
- 주문 다운로드 비동기 잡 (요청 → 백그라운드 생성 → 진행률 조회 → 완료 파일 받기): 지금은 동기 스트리밍 응답이고
  진행률은 estimate 건수로 안내한다. 생성이 HTTP 타임아웃에 걸릴 만큼 커지면 도입
- 주문 다운로드 결제일시 열(PAID_AT): orders.paid_at이 생기는 이벤트 마이그레이션(V9, 요구사항 A-1)과 같은 작업에서
  OrderDownloadColumn에 추가한다. 지금은 컬럼이 없어 주문일시(ordered_at)만 내린다
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
