package com.crossborder.oms.service.stock;

import com.crossborder.common.entity.order.OrderItem;
import com.crossborder.common.entity.product.SaleProductItem;
import com.crossborder.oms.dto.stock.AllocationBackfillResponse;
import com.crossborder.oms.dto.stock.AllocationConsistencyResponse;
import com.crossborder.oms.repository.OrderItemRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 재고 할당 관리 커맨드 (관리자 수동 실행, 동기).
 * <ul>
 *   <li>소급 할당: allocated_at IS NULL이고 취소되지 않은(status != 'CANCELED') 주문을 할당한다.
 *       DELIVERED 주문은 조회되지만 할당하지 않고 이상 데이터로 집계한다 — 출고(배송)가 끝난 주문을 지금 할당하면
 *       allocated_stock이 부풀어 재고 정합이 깨진다. 운영자가 응답의 주문 id로 확인한다. 정상 흐름(등록·매핑 완료 시 자동 할당)에서 빠진
 *       예외 상황이나 allocated_at 도입 이전 데이터 정리용이다. 등록과 같은 묶음 실행기(ChunkedAllocationExecutor)를 쓴다.
 *       멱등 — 주문 행을 잠근 뒤 allocated_at IS NULL을 다시 확인하고 같은 트랜잭션에서 시각을 기록한다.
 *       매핑안됨 주문은 전개할 수 없어 건너뛴다 (매핑 완료 시 자동 할당).</li>
 *   <li>정합 검증: 할당 완료 주문의 유효 항목 전개 합과 products.allocated_stock을 비교한다.</li>
 * </ul>
 * 기대 할당 계산은 출고 차감(출고 Phase)이 없는 현재 기준이다 — 출고가 생기면 출고분을 빼도록 바꿔야 한다.
 */
@Service
public class AllocationAdminService {

    private static final Logger log = LoggerFactory.getLogger(AllocationAdminService.class);
    /** 소급 대상. 취소된 주문은 할당할 것이 없어 대상이 아니다 */
    private static final String UNALLOCATED = "allocated_at IS NULL AND status != 'CANCELED'";
    private static final String ALLOCATABLE = UNALLOCATED + " AND status != 'DELIVERED' AND mapping_pending = FALSE";
    /** 응답에 담는 이상 주문 id 최대 개수 (건수는 전부 센다) */
    private static final int MAX_ANOMALY_IDS = 100;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final OrderItemRepository orderItemRepository;
    private final StockAllocator stockAllocator;
    private final ChunkedAllocationExecutor chunkedAllocationExecutor;
    private final Clock clock;

    public AllocationAdminService(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate,
                                  OrderItemRepository orderItemRepository, StockAllocator stockAllocator,
                                  ChunkedAllocationExecutor chunkedAllocationExecutor, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.orderItemRepository = orderItemRepository;
        this.stockAllocator = stockAllocator;
        this.chunkedAllocationExecutor = chunkedAllocationExecutor;
        this.clock = clock;
    }

    /**
     * 소급 대상(UNALLOCATED)을 id 순으로 한 묶음(500)씩 조회해 할당한다.
     */
    public AllocationBackfillResponse backfill() {
        int processed = 0;
        int allocated = 0;
        int failed = 0;
        int anomalies = 0;
        List<Long> anomalyOrderIds = new ArrayList<>();
        long lastId = 0;
        while (true) {
            List<Map<String, Object>> page = jdbcTemplate.queryForList(
                    "SELECT id, status, mapping_pending FROM orders WHERE " + UNALLOCATED + " AND id > ? ORDER BY id LIMIT "
                            + ChunkedAllocationExecutor.CHUNK_SIZE, lastId);
            if (page.isEmpty()) {
                break;
            }
            processed += page.size();
            lastId = ((Number) page.getLast().get("id")).longValue();
            List<Long> candidates = new ArrayList<>();
            for (Map<String, Object> row : page) {
                long id = ((Number) row.get("id")).longValue();
                if ("DELIVERED".equals(row.get("status"))) {
                    anomalies++;
                    if (anomalyOrderIds.size() < MAX_ANOMALY_IDS) {
                        anomalyOrderIds.add(id);
                    }
                } else if (!Boolean.TRUE.equals(row.get("mapping_pending"))) {
                    candidates.add(id);
                }
            }
            if (candidates.isEmpty()) {
                continue;
            }
            // 락 키 산정용 전개 (할당 수량은 행을 잠근 뒤 다시 전개한다)
            Map<Long, Map<Long, Integer>> keyExpansion = expandByOrder(candidates);
            ChunkedAllocationExecutor.Outcome<Long> outcome = chunkedAllocationExecutor.execute(candidates,
                    keyExpansion::get, this::allocateLocked);
            allocated += outcome.workCount();
            failed += outcome.failures().size();
        }
        int skipped = processed - allocated - failed - anomalies;
        if (anomalies > 0) {
            log.warn("소급 할당: 미할당 DELIVERED 주문 발견 (할당하지 않음, 운영자 확인 필요). count={}, orderIds={}",
                    anomalies, anomalyOrderIds);
        }
        log.info("소급 할당 완료: processed={}, allocated={}, skipped={}, failed={}, anomalies={}",
                processed, allocated, skipped, failed, anomalies);
        return new AllocationBackfillResponse(processed, allocated, skipped, failed, anomalies, anomalyOrderIds);
    }

    /**
     * 트랜잭션·제품 락 안에서 실행: 대상 주문 행 잠금·재확인 → 재전개 → 할당 → allocated_at.
     * 행 잠금 전 전개로 정한 락 키 안에 재전개 결과가 들어간다 — 그 사이 취소가 들어와도 수량은 줄기만 한다.
     *
     * @return 할당한 주문 수 (다른 경로가 먼저 할당한 주문은 빠진다)
     */
    private int allocateLocked(List<Long> candidateIds) {
        List<Long> lockedIds = namedJdbcTemplate.queryForList(
                "SELECT id FROM orders WHERE id IN (:ids) AND " + ALLOCATABLE + " FOR UPDATE",
                new MapSqlParameterSource("ids", candidateIds), Long.class);
        if (lockedIds.isEmpty()) {
            return 0;
        }
        stockAllocator.increase(StockAllocator.sum(expandByOrder(lockedIds).values()));
        namedJdbcTemplate.update("UPDATE orders SET allocated_at = :now WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", lockedIds).addValue("now", now()));
        return lockedIds.size();
    }

    private Map<Long, Map<Long, Integer>> expandByOrder(List<Long> orderIds) {
        List<OrderItem> items = orderItemRepository.findByOrderIdIn(orderIds);
        Map<Long, List<SaleProductItem>> compositions = stockAllocator.loadCompositions(items.stream()
                .map(OrderItem::getSaleProductId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, List<OrderItem>> byOrder = items.stream().collect(Collectors.groupingBy(OrderItem::getOrderId));
        Map<Long, Map<Long, Integer>> allocations = new HashMap<>();
        for (Long orderId : orderIds) {
            allocations.put(orderId, StockAllocator.expand(byOrder.getOrDefault(orderId, List.of()), compositions));
        }
        return allocations;
    }

    /**
     * 할당 완료 주문의 유효 항목 전개 합(기대값)과 products.allocated_stock(실제값)을 제품별로 비교한다.
     */
    public AllocationConsistencyResponse checkConsistency() {
        Map<Long, Integer> expected = new HashMap<>();
        jdbcTemplate.query("""
                SELECT product_id, SUM(qty) AS qty FROM (
                    SELECT spi.product_id, oi.quantity * spi.quantity AS qty
                    FROM order_items oi
                             JOIN orders o ON o.id = oi.order_id
                             JOIN sale_product_items spi ON spi.sale_product_id = oi.sale_product_id
                    WHERE o.allocated_at IS NOT NULL AND oi.status = 'ORDERED' AND oi.item_type = 'SALE_PRODUCT'
                    UNION ALL
                    SELECT oi.product_id, oi.quantity
                    FROM order_items oi
                             JOIN orders o ON o.id = oi.order_id
                    WHERE o.allocated_at IS NOT NULL AND oi.status = 'ORDERED' AND oi.item_type = 'GIFT_PRODUCT'
                ) t
                GROUP BY product_id
                """, rs -> {
            expected.put(rs.getLong("product_id"), rs.getInt("qty"));
        });

        List<AllocationConsistencyResponse.Mismatch> mismatches = new ArrayList<>();
        int[] checked = {0};
        jdbcTemplate.query("SELECT id, sku, allocated_stock FROM products ORDER BY id", rs -> {
            checked[0]++;
            long productId = rs.getLong("id");
            int actual = rs.getInt("allocated_stock");
            int expectedQty = expected.getOrDefault(productId, 0);
            if (actual != expectedQty) {
                mismatches.add(new AllocationConsistencyResponse.Mismatch(productId, rs.getString("sku"), expectedQty, actual));
            }
        });
        Long unallocated = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE " + ALLOCATABLE, Long.class);
        Long pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE " + UNALLOCATED + " AND mapping_pending = TRUE", Long.class);
        return new AllocationConsistencyResponse(checked[0], mismatches, unallocated, pending);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
