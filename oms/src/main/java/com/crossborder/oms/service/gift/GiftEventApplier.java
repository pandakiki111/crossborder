package com.crossborder.oms.service.gift;

import com.crossborder.common.entity.gift.GiftEvent;
import com.crossborder.common.entity.gift.GiftEventActivePeriod;
import com.crossborder.common.entity.gift.GiftEventCondition;
import com.crossborder.common.entity.gift.GiftEventItem;
import com.crossborder.infra.jpa.AuditorContext;
import com.crossborder.infra.lock.DistributedLockManager;
import com.crossborder.infra.lock.LockAcquisitionException;
import com.crossborder.oms.repository.GiftEventActivePeriodRepository;
import com.crossborder.oms.repository.GiftEventConditionRepository;
import com.crossborder.oms.repository.GiftEventItemRepository;
import com.crossborder.oms.repository.GiftEventRepository;
import com.crossborder.oms.service.gift.GiftEventEvaluator.Candidate;
import com.crossborder.oms.service.gift.GiftEventEvaluator.Decision;
import com.crossborder.oms.service.gift.GiftEventEvaluator.OrderFacts;
import com.crossborder.oms.service.gift.GiftEventEvaluator.PurchasedItem;
import com.crossborder.oms.service.gift.GiftEventEvaluator.Rule;
import com.crossborder.oms.service.stock.StockAllocator;
import com.crossborder.oms.service.support.InClause;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사은품 이벤트 실행기: 주문들을 판정(GiftEventEvaluator)하고 증정을 기록한다. 시딩(applyGiftEvents)과 수집(예정)이 같은 경로를 쓴다.
 * <ol>
 *   <li>사전 판정(락 없이): 묶음(500주문)의 대상 이벤트·조건·금액으로 증정 가능성이 있는 이벤트와 후보 품목을 고른다</li>
 *   <li>락: 그 이벤트들(gift-event:{id}) + 할당된 주문이면 후보 품목 제품(stock:product:{id})을 <b>한 번의 정렬 멀티락</b>으로 잡는다
 *       — 키 전역 정렬이라 제품 락을 쓰는 다른 경로(취소·할당)와 교착이 없다</li>
 *   <li>새 트랜잭션: 주문 행 잠금(제품 락 → 주문 행 순서 규칙) → 상태·지급 이력·품목 잔여를 다시 읽고 주문 순서대로 판정·선택
 *       → 지급 기록(grants) + EVENT 사은품 행 + granted_qty 증가 + 할당된 주문이면 사은품 할당 → 커밋 후 락 해제</li>
 *   <li>묶음이 락 타임아웃·DB 오류로 실패하면 주문 단위로 다시 실행하고, 그래도 실패한 주문은 실패로 돌려준다
 *       (주문 등록은 이미 끝났으므로 영향 없음 — 지급 이력이 멱등 기준이라 나중에 재평가하면 된다)</li>
 * </ol>
 * 판정 제외: 취소된 주문, 이미 그 이벤트를 받은 주문.
 * 매핑안됨 주문도 미루지 않고 판정한다 — 매핑안됨 항목은 판매상품이 없어 상품 조건 매칭에서만 빠지고 금액 조건은 그대로 본다.
 * 매핑 완료 후 다시 판정하지 않는다 (매핑 완료 응답이 안내하고, 필요하면 수동 증정). 매핑안됨 주문은 미할당이라 증정분도
 * 할당하지 않고, 매핑 완료 시 주문 전체 할당에 사은품 행이 함께 들어간다.
 */
@Component
public class GiftEventApplier {

    private static final Logger log = LoggerFactory.getLogger(GiftEventApplier.class);
    static final int CHUNK_SIZE = 500;
    private static final String EVENT_LOCK_PREFIX = "gift-event:";

    private static final String SELECT_ORDERS = """
            SELECT id, status, allocated_at, ordered_at, paid_at, paid_amount
            FROM orders WHERE id IN (:ids)
            """;
    private static final String LOCK_ORDERS = "SELECT id FROM orders WHERE id IN (:ids) FOR UPDATE";
    private static final String SELECT_ITEMS = """
            SELECT oi.order_id, oi.brand_id, oi.sale_product_id, sp.code, oi.channel_option_code, oi.quantity
            FROM order_items oi JOIN sale_products sp ON sp.id = oi.sale_product_id
            WHERE oi.order_id IN (:ids) AND oi.status = 'ORDERED' AND oi.item_type = 'SALE_PRODUCT'
            """;
    private static final String SELECT_BRANDS = """
            SELECT DISTINCT order_id, brand_id FROM order_items
            WHERE order_id IN (:ids) AND status = 'ORDERED' AND item_type = 'SALE_PRODUCT'
            """;
    private static final String SELECT_SKU_UNITS = """
            SELECT spi.sale_product_id, p.sku, spi.quantity
            FROM sale_product_items spi JOIN products p ON p.id = spi.product_id
            WHERE spi.sale_product_id IN (:ids)
            """;
    private static final String SELECT_GRANTS =
            "SELECT gift_event_id, order_id FROM gift_event_grants WHERE order_id IN (:ids)";
    private static final String SELECT_GRANTED =
            "SELECT id, limit_qty, granted_qty FROM gift_event_items WHERE gift_event_id IN (:ids)";
    private static final String INSERT_GRANT = """
            INSERT INTO gift_event_grants (gift_event_id, order_id, granted_at, created_user_id)
            VALUES (:eventId, :orderId, :now, :userId)
            """;
    private static final String INSERT_GIFT_ITEM = """
            INSERT INTO order_items (order_id, brand_id, item_type, product_id, gift_source, gift_event_id,
                                     quantity, unit_price, status, created_at, created_user_id, updated_at, updated_user_id)
            VALUES (:orderId, :brandId, 'GIFT_PRODUCT', :productId, 'EVENT', :eventId,
                    :quantity, 0, 'ORDERED', :now, :userId, :now, :userId)
            """;
    private static final String INCREASE_GRANTED =
            "UPDATE gift_event_items SET granted_qty = granted_qty + :quantity WHERE id = :id";

    private final GiftEventRepository eventRepository;
    private final GiftEventConditionRepository conditionRepository;
    private final GiftEventItemRepository itemRepository;
    private final GiftEventActivePeriodRepository periodRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final StockAllocator stockAllocator;
    private final DistributedLockManager lockManager;
    private final TransactionTemplate transaction;
    private final RandomGenerator random;
    private final Long systemUserId;

    public GiftEventApplier(GiftEventRepository eventRepository, GiftEventConditionRepository conditionRepository,
                            GiftEventItemRepository itemRepository, GiftEventActivePeriodRepository periodRepository,
                            NamedParameterJdbcTemplate jdbc, StockAllocator stockAllocator,
                            DistributedLockManager lockManager, PlatformTransactionManager transactionManager,
                            RandomGenerator giftRandom,
                            @Value("${crossborder.audit.system-user-id:1}") Long systemUserId) {
        this.eventRepository = eventRepository;
        this.conditionRepository = conditionRepository;
        this.itemRepository = itemRepository;
        this.periodRepository = periodRepository;
        this.jdbc = jdbc;
        this.stockAllocator = stockAllocator;
        this.lockManager = lockManager;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.random = giftRandom;
        this.systemUserId = systemUserId;
    }

    /**
     * @param grants       지급 기록 수 (이벤트 × 주문)
     * @param giftedOrders 사은품을 1건 이상 받은 주문 수
     * @param failedOrders 락·DB 오류로 증정하지 못한 주문 (재평가 대상)
     */
    public record Outcome(int grants, int giftedOrders, Set<Long> failedOrders) {

        static final Outcome NONE = new Outcome(0, 0, Set.of());

        Outcome plus(Outcome other) {
            Set<Long> failed = new LinkedHashSet<>(failedOrders);
            failed.addAll(other.failedOrders);
            return new Outcome(grants + other.grants, giftedOrders + other.giftedOrders, failed);
        }
    }

    /** 주문들을 판정·증정한다. 입력 순서가 선착순 순서다 (SEQUENTIAL·RANDOM 한도) */
    public Outcome apply(List<Long> orderIds) {
        Long userId = AuditorContext.get().orElse(systemUserId);
        Outcome total = Outcome.NONE;
        for (List<Long> chunk : InClause.partition(orderIds, CHUNK_SIZE)) {
            try {
                total = total.plus(applyChunk(chunk, userId));
            } catch (DataAccessException | LockAcquisitionException e) {
                log.warn("사은품 증정 묶음 실패, 주문 단위로 재시도: size={}, cause={}", chunk.size(),
                        NestedExceptionUtils.getMostSpecificCause(e).getMessage());
                for (Long orderId : chunk) {
                    try {
                        total = total.plus(applyChunk(List.of(orderId), userId));
                    } catch (DataAccessException | LockAcquisitionException single) {
                        log.warn("사은품 증정 실패: orderId={}, cause={}", orderId,
                                NestedExceptionUtils.getMostSpecificCause(single).getMessage());
                        total = total.plus(new Outcome(0, 0, Set.of(orderId)));
                    }
                }
            }
        }
        return total;
    }

    private Outcome applyChunk(List<Long> orderIds, Long userId) {
        Map<Long, OrderState> orders = loadOrders(orderIds);
        if (orders.isEmpty()) {
            return Outcome.NONE;
        }
        Map<Long, Rule> rules = loadRules(orders.values());
        // 사전 판정: 증정 가능성이 있는 (주문, 이벤트) — 한도는 락 안에서 본다
        Map<Long, List<Long>> eligible = new LinkedHashMap<>();
        Set<Long> productKeys = new TreeSet<>();
        Set<Long> eventKeys = new TreeSet<>();
        for (OrderState order : orders.values()) {
            for (Rule rule : rules.values()) {
                if (GiftEventEvaluator.giftQuantity(rule, order.facts()) > 0) {
                    eligible.computeIfAbsent(order.id(), k -> new ArrayList<>()).add(rule.event().getId());
                    eventKeys.add(rule.event().getId());
                    if (order.allocated()) {
                        rule.items().forEach(c -> productKeys.add(c.productId()));
                    }
                }
            }
        }
        if (eligible.isEmpty()) {
            return Outcome.NONE;
        }
        List<String> keys = new ArrayList<>(eventKeys.stream().map(id -> EVENT_LOCK_PREFIX + id).toList());
        keys.addAll(StockAllocator.lockKeys(productKeys));
        return lockManager.executeWithLocks(keys,
                () -> transaction.execute(status -> grant(eligible, rules, userId)));
    }

    /** 락·트랜잭션 안: 주문 잠금 후 상태·지급 이력·잔여를 다시 읽고 확정한다 */
    private Outcome grant(Map<Long, List<Long>> eligible, Map<Long, Rule> rules, Long userId) {
        List<Long> orderIds = List.copyOf(eligible.keySet());
        jdbc.query(LOCK_ORDERS, new MapSqlParameterSource("ids", orderIds), rs -> {
        });
        Map<Long, OrderState> orders = loadOrders(orderIds);
        Set<String> granted = new HashSet<>();
        jdbc.query(SELECT_GRANTS, new MapSqlParameterSource("ids", orderIds),
                rs -> {
                    granted.add(rs.getLong("gift_event_id") + ":" + rs.getLong("order_id"));
                });
        Map<Long, Integer> remaining = loadRemaining(rules.keySet());

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        List<MapSqlParameterSource> grantRows = new ArrayList<>();
        List<MapSqlParameterSource> itemRows = new ArrayList<>();
        Map<Long, Integer> grantedQty = new TreeMap<>();
        Map<Long, Integer> allocation = new TreeMap<>();
        Set<Long> gifted = new HashSet<>();
        for (Long orderId : orderIds) {
            OrderState order = orders.get(orderId);
            if (order == null) {
                continue;
            }
            for (Long eventId : eligible.get(orderId)) {
                if (!granted.add(eventId + ":" + orderId)) {
                    continue;
                }
                Rule rule = withRemaining(rules.get(eventId), remaining);
                List<Decision> decisions = GiftEventEvaluator.evaluate(rule, order.facts(), random);
                if (decisions.isEmpty()) {
                    continue;
                }
                grantRows.add(new MapSqlParameterSource().addValue("eventId", eventId).addValue("orderId", orderId)
                        .addValue("now", now).addValue("userId", userId));
                for (Decision d : decisions) {
                    itemRows.add(new MapSqlParameterSource().addValue("orderId", orderId)
                            .addValue("brandId", rule.event().getBrandId()).addValue("productId", d.productId())
                            .addValue("eventId", eventId).addValue("quantity", d.quantity())
                            .addValue("now", now).addValue("userId", userId));
                    grantedQty.merge(d.eventItemId(), d.quantity(), Integer::sum);
                    remaining.computeIfPresent(d.eventItemId(), (id, left) -> left - d.quantity());
                    if (order.allocated()) {
                        allocation.merge(d.productId(), d.quantity(), Integer::sum);
                    }
                }
                gifted.add(orderId);
            }
        }
        if (grantRows.isEmpty()) {
            return Outcome.NONE;
        }
        jdbc.batchUpdate(INSERT_GRANT, grantRows.toArray(MapSqlParameterSource[]::new));
        jdbc.batchUpdate(INSERT_GIFT_ITEM, itemRows.toArray(MapSqlParameterSource[]::new));
        jdbc.batchUpdate(INCREASE_GRANTED, grantedQty.entrySet().stream()
                .map(e -> new MapSqlParameterSource().addValue("id", e.getKey()).addValue("quantity", e.getValue()))
                .toArray(MapSqlParameterSource[]::new));
        stockAllocator.increase(allocation);
        return new Outcome(grantRows.size(), gifted.size(), Set.of());
    }

    // ---------------------------------------------------------------- 판정 입력

    private record OrderState(Long id, boolean allocated, OrderFacts facts) {
    }

    /** 판정 대상 주문 (취소 제외)과 그 유효·매핑완료 구매 항목 (매핑안됨 항목은 sale_products 조인에서 빠진다) */
    private Map<Long, OrderState> loadOrders(List<Long> orderIds) {
        MapSqlParameterSource ids = new MapSqlParameterSource("ids", orderIds);
        record Row(Long id, boolean allocated, LocalDateTime orderedAt, LocalDateTime paidAt, BigDecimal paidAmount) {
        }
        Map<Long, Row> rows = new LinkedHashMap<>();
        jdbc.query(SELECT_ORDERS, ids, rs -> {
            if (!"CANCELED".equals(rs.getString("status"))) {
                rows.put(rs.getLong("id"), new Row(rs.getLong("id"), rs.getTimestamp("allocated_at") != null,
                        rs.getTimestamp("ordered_at").toLocalDateTime(),
                        rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toLocalDateTime(),
                        rs.getBigDecimal("paid_amount")));
            }
        });
        if (rows.isEmpty()) {
            return Map.of();
        }
        record ItemRow(Long orderId, Long brandId, Long saleProductId, String code, String option, int quantity) {
        }
        List<ItemRow> itemRows = new ArrayList<>();
        jdbc.query(SELECT_ITEMS, new MapSqlParameterSource("ids", List.copyOf(rows.keySet())), rs -> {
            itemRows.add(new ItemRow(rs.getLong("order_id"), rs.getLong("brand_id"), rs.getLong("sale_product_id"),
                    rs.getString("code"), rs.getString("channel_option_code"), rs.getInt("quantity")));
        });
        Map<Long, Map<String, Integer>> skuUnits = new HashMap<>();
        Set<Long> saleProductIds = itemRows.stream().map(ItemRow::saleProductId).collect(Collectors.toSet());
        if (!saleProductIds.isEmpty()) {
            jdbc.query(SELECT_SKU_UNITS, new MapSqlParameterSource("ids", saleProductIds), rs -> {
                skuUnits.computeIfAbsent(rs.getLong("sale_product_id"), k -> new HashMap<>())
                        .merge(rs.getString("sku"), rs.getInt("quantity"), Integer::sum);
            });
        }
        Map<Long, Set<Long>> brands = new HashMap<>();
        jdbc.query(SELECT_BRANDS, new MapSqlParameterSource("ids", List.copyOf(rows.keySet())), rs -> {
            brands.computeIfAbsent(rs.getLong("order_id"), k -> new HashSet<>()).add(rs.getLong("brand_id"));
        });
        Map<Long, List<PurchasedItem>> items = itemRows.stream().collect(Collectors.groupingBy(ItemRow::orderId,
                Collectors.mapping(i -> new PurchasedItem(i.brandId(), i.code(), i.option() == null ? "" : i.option(),
                        i.quantity(), skuUnits.getOrDefault(i.saleProductId(), Map.of())), Collectors.toList())));
        Map<Long, OrderState> orders = new LinkedHashMap<>();
        for (Long orderId : orderIds) {
            Row row = rows.get(orderId);
            if (row != null) {
                orders.put(orderId, new OrderState(orderId, row.allocated(), new OrderFacts(orderId, row.orderedAt(),
                        row.paidAt(), row.paidAmount(), brands.getOrDefault(orderId, Set.of()),
                        items.getOrDefault(orderId, List.of()))));
            }
        }
        return orders;
    }

    /** 주문들의 브랜드·기준 시각 범위와 겹치는 이벤트 (조건·활성 구간·품목 포함, 잔여는 락 밖 값) */
    private Map<Long, Rule> loadRules(Collection<OrderState> orders) {
        Set<Long> brandIds = orders.stream().flatMap(o -> o.facts().brandIds().stream()).collect(Collectors.toSet());
        if (brandIds.isEmpty()) {
            return Map.of();
        }
        LocalDateTime from = orders.stream().flatMap(o -> times(o.facts())).min(Comparator.naturalOrder()).orElseThrow();
        LocalDateTime to = orders.stream().flatMap(o -> times(o.facts())).max(Comparator.naturalOrder()).orElseThrow();
        List<GiftEvent> events = eventRepository.findOverlapping(brandIds, from, to);
        if (events.isEmpty()) {
            return Map.of();
        }
        Set<Long> eventIds = events.stream().map(GiftEvent::getId).collect(Collectors.toSet());
        Map<Long, List<GiftEventCondition>> conditions = conditionRepository.findByGiftEventIdInOrderByIdAsc(eventIds)
                .stream().collect(Collectors.groupingBy(GiftEventCondition::getGiftEventId));
        Map<Long, List<GiftEventActivePeriod>> periods = periodRepository.findByGiftEventIdInOrderByActiveFromAsc(eventIds)
                .stream().collect(Collectors.groupingBy(GiftEventActivePeriod::getGiftEventId));
        Map<Long, List<Candidate>> items = itemRepository.findByGiftEventIdInOrderByPriorityAscIdAsc(eventIds).stream()
                .collect(Collectors.groupingBy(GiftEventItem::getGiftEventId, Collectors.mapping(
                        i -> new Candidate(i.getId(), i.getProductId(), i.getPriority(),
                                i.getLimitQty() == null ? null : i.getLimitQty() - i.getGrantedQty()),
                        Collectors.toList())));
        Map<Long, Rule> rules = new TreeMap<>();
        for (GiftEvent event : events) {
            rules.put(event.getId(), new Rule(event, conditions.getOrDefault(event.getId(), List.of()),
                    periods.getOrDefault(event.getId(), List.of()), items.getOrDefault(event.getId(), List.of())));
        }
        return rules;
    }

    private static java.util.stream.Stream<LocalDateTime> times(OrderFacts facts) {
        return java.util.stream.Stream.of(facts.orderedAt(), facts.paidAt()).filter(Objects::nonNull);
    }

    /** 품목 잔여 (락 안에서 다시 읽은 값). 한도 없는 품목은 맵에 없다 */
    private Map<Long, Integer> loadRemaining(Collection<Long> eventIds) {
        Map<Long, Integer> remaining = new HashMap<>();
        jdbc.query(SELECT_GRANTED, new MapSqlParameterSource("ids", eventIds), rs -> {
            int limit = rs.getInt("limit_qty");
            if (!rs.wasNull()) {
                remaining.put(rs.getLong("id"), limit - rs.getInt("granted_qty"));
            }
        });
        return remaining;
    }

    private static Rule withRemaining(Rule rule, Map<Long, Integer> remaining) {
        List<Candidate> items = rule.items().stream()
                .map(c -> new Candidate(c.eventItemId(), c.productId(), c.priority(), remaining.get(c.eventItemId())))
                .toList();
        return new Rule(rule.event(), rule.conditions(), rule.periods(), items);
    }
}
