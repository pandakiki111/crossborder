package com.crossborder.oms.service.order;

import com.crossborder.common.entity.order.Order;
import com.crossborder.common.entity.order.OrderItem;
import com.crossborder.common.entity.order.StatusTargetType;
import com.crossborder.infra.lock.LockAcquisitionException;
import com.crossborder.oms.service.stock.ChunkedAllocationExecutor;
import com.crossborder.oms.service.stock.StockAllocator;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 주문 대량 쓰기 (JDBC batch). 엑셀 시딩·마켓 수집 등 대량 경로 공용.
 * <p>
 * JPA saveAll은 IDENTITY 전략에서 INSERT 배치가 꺼져 건별 왕복이 되므로 대량 경로는 JDBC로 쓴다.
 * 도메인 규칙은 OrderDraft를 엔티티 팩토리로 만들 때 이미 검증됐고, 여기서는 값만 옮긴다.
 * 엔티티를 거치지 않으므로 감사 컬럼(created_*, updated_*)과 최초 상태 이력은 여기서 직접 채운다.
 * <p>
 * 트랜잭션: 500주문 단위로 커밋 (ChunkedAllocationExecutor — 소급 할당과 같은 실행 규칙). 묶음이 DB 오류나 락 타임아웃으로 실패하면 그 묶음만 주문 단위로 다시 넣어
 * 원인 주문만 실패시킨다 (주문 단위 원자성 유지 — 한 주문의 주문/항목/이력/재고 할당은 항상 같이 들어가거나 같이 빠진다).
 * <p>
 * 재고 할당: 묶음의 제품별 전개 수량을 합산해 제품 락(정렬 멀티락)을 한 번 잡고, 같은 트랜잭션에서 allocated_stock을
 * 올리고 allocated_at을 기록한다. 락은 커밋 후 해제한다. 등록됐는데 미할당인 주문은 남지 않는다.
 * 묶음 단위로 락을 잡는 이유: 주문마다 락·커밋하면 대량 시딩의 왕복이 주문 수만큼 늘어난다.
 */
@Component
class OrderBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(OrderBatchWriter.class);

    private static final String CHANNEL_ORDER_UK = "uk_orders_channel_order";

    private static final String INSERT_ORDER = """
            INSERT INTO orders (order_no, sales_channel_id, channel_order_no, company_id, status,
                                mapping_pending, allocated_at, total_item_amount, paid_amount, currency, orderer_name,
                                receiver_name, receiver_phone, receiver_zipcode, receiver_address, delivery_memo, market_memo,
                                ordered_at, paid_at, created_at, created_user_id, updated_at, updated_user_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_ITEM = """
            INSERT INTO order_items (order_id, brand_id, item_type, sale_product_id, product_id, gift_source,
                                     gift_event_id, channel_product_code, channel_option_code, quantity, unit_price,
                                     status, created_at, created_user_id, updated_at, updated_user_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_HISTORY = """
            INSERT INTO order_status_history (order_id, target_type, target_id, previous_status, current_status,
                                              changed_by, created_at)
            VALUES (?, ?, ?, NULL, ?, ?, ?)
            """;
    private static final String SELECT_IDS = "SELECT id, order_no FROM orders WHERE order_no IN (:orderNos)";

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final StockAllocator stockAllocator;
    private final ChunkedAllocationExecutor chunkedAllocationExecutor;

    OrderBatchWriter(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate,
                     StockAllocator stockAllocator, ChunkedAllocationExecutor chunkedAllocationExecutor) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.stockAllocator = stockAllocator;
        this.chunkedAllocationExecutor = chunkedAllocationExecutor;
    }

    /**
     * 묶음 실행(락·트랜잭션·주문 단위 재시도)은 ChunkedAllocationExecutor가 하고, 여기서는 묶음 작업(INSERT + 할당)과
     * 실패 원인 → 등록 결과 변환만 한다.
     *
     * @return drafts와 같은 순서의 결과
     */
    List<OrderRegistrationResult> write(List<OrderDraft> drafts, Long userId) {
        ChunkedAllocationExecutor.Outcome<OrderDraft> outcome = chunkedAllocationExecutor.execute(drafts,
                OrderDraft::allocation,
                chunk -> {
                    insert(chunk, userId);
                    stockAllocator.increase(StockAllocator.sum(
                            chunk.stream().filter(OrderDraft::allocates).map(OrderDraft::allocation).toList()));
                    return chunk.size();
                });
        List<OrderRegistrationResult> results = new ArrayList<>(drafts.size());
        for (OrderDraft draft : drafts) {
            RuntimeException failure = outcome.failures().get(draft);
            results.add(failure == null ? registered(draft) : toResult(draft, failure));
        }
        return results;
    }

    private static OrderRegistrationResult toResult(OrderDraft draft, RuntimeException failure) {
        if (failure instanceof LockAcquisitionException e) {
            log.warn("주문 등록 실패 (재고 락 타임아웃): channelOrderNo={}, keys={}",
                    draft.order().getChannelOrderNo(), e.getKeys());
            return OrderRegistrationResult.failed("재고 할당 락 획득 실패 - 같은 상품을 처리 중인 다른 요청이 있습니다. 다시 시도하세요");
        }
        // 사전 중복 체크 이후 다른 업로드·수집이 같은 주문을 먼저 넣은 경우
        if (failure instanceof DuplicateKeyException
                && NestedExceptionUtils.getMostSpecificCause(failure).getMessage().contains(CHANNEL_ORDER_UK)) {
            return OrderRegistrationResult.duplicate();
        }
        return failed(draft, (DataAccessException) failure);
    }

    private void insert(List<OrderDraft> chunk, Long userId) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        jdbcTemplate.batchUpdate(INSERT_ORDER, chunk.stream().map(d -> orderRow(d, now, userId)).toList());

        Map<String, Long> ids = new HashMap<>();
        namedJdbcTemplate.query(SELECT_IDS,
                new MapSqlParameterSource("orderNos", chunk.stream().map(d -> d.order().getOrderNo()).toList()),
                rs -> {
                    ids.put(rs.getString("order_no"), rs.getLong("id"));
                });

        List<Object[]> itemRows = new ArrayList<>();
        List<Object[]> historyRows = new ArrayList<>(chunk.size());
        for (OrderDraft draft : chunk) {
            Long orderId = ids.get(draft.order().getOrderNo());
            draft.items().forEach(item -> itemRows.add(itemRow(orderId, item, now, userId)));
            historyRows.add(new Object[]{orderId, StatusTargetType.ORDER.name(), orderId,
                    draft.order().getStatus().name(), userId, now});
        }
        jdbcTemplate.batchUpdate(INSERT_ITEM, itemRows);
        jdbcTemplate.batchUpdate(INSERT_HISTORY, historyRows);
    }

    private static Object[] orderRow(OrderDraft draft, LocalDateTime now, Long userId) {
        Order o = draft.order();
        return new Object[]{o.getOrderNo(), o.getSalesChannelId(), o.getChannelOrderNo(), o.getCompanyId(),
                o.getStatus().name(), o.isMappingPending(), draft.allocates() ? now : null,
                o.getTotalItemAmount(), o.getPaidAmount(), o.getCurrency(), o.getOrdererName(),
                o.getReceiverName(), o.getReceiverPhone(), o.getReceiverZipcode(), o.getReceiverAddress(),
                o.getDeliveryMemo(), o.getMarketMemo(), o.getOrderedAt(), o.getPaidAt(), now, userId, now, userId};
    }

    private static Object[] itemRow(Long orderId, OrderItem i, LocalDateTime now, Long userId) {
        return new Object[]{orderId, i.getBrandId(), i.getItemType().name(), i.getSaleProductId(), i.getProductId(),
                i.getGiftSource() == null ? null : i.getGiftSource().name(), i.getGiftEventId(),
                i.getChannelProductCode(), i.getChannelOptionCode(), i.getQuantity(), i.getUnitPrice(),
                i.getStatus().name(), now, userId, now, userId};
    }

    private static OrderRegistrationResult registered(OrderDraft draft) {
        return OrderRegistrationResult.registered(draft.order().getOrderNo(), draft.order().isMappingPending());
    }

    private static OrderRegistrationResult failed(OrderDraft draft, DataAccessException e) {
        log.warn("주문 등록 실패: channelOrderNo={}", draft.order().getChannelOrderNo(), e);
        return OrderRegistrationResult.failed("DB 저장 실패 - "
                + NestedExceptionUtils.getMostSpecificCause(e).getMessage());
    }
}
