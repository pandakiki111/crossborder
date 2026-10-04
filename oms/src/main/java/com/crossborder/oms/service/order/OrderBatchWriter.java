package com.crossborder.oms.service.order;

import com.crossborder.common.entity.order.Order;
import com.crossborder.common.entity.order.OrderItem;
import com.crossborder.common.entity.order.StatusTargetType;
import com.crossborder.oms.service.support.InClause;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 대량 쓰기 (JDBC batch). 엑셀 시딩·마켓 수집 등 대량 경로 공용.
 * <p>
 * JPA saveAll은 IDENTITY 전략에서 INSERT 배치가 꺼져 건별 왕복이 되므로 대량 경로는 JDBC로 쓴다.
 * 도메인 규칙은 OrderDraft를 엔티티 팩토리로 만들 때 이미 검증됐고, 여기서는 값만 옮긴다.
 * 엔티티를 거치지 않으므로 감사 컬럼(created_*, updated_*)과 최초 상태 이력은 여기서 직접 채운다.
 * <p>
 * 트랜잭션: CHUNK_SIZE 주문 단위로 커밋. 묶음이 DB 오류로 실패하면 그 묶음만 주문 단위로 다시 넣어
 * 원인 주문만 실패시킨다 (주문 단위 원자성 유지 — 한 주문의 주문/항목/이력은 항상 같이 들어가거나 같이 빠진다).
 */
@Component
class OrderBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(OrderBatchWriter.class);

    static final int CHUNK_SIZE = 500;
    private static final String CHANNEL_ORDER_UK = "uk_orders_channel_order";

    private static final String INSERT_ORDER = """
            INSERT INTO orders (order_no, sales_channel_id, channel_order_no, company_id, status,
                                mapping_pending, total_item_amount, paid_amount, currency, orderer_name, receiver_name, receiver_phone,
                                receiver_zipcode, receiver_address, delivery_memo, market_memo, ordered_at,
                                created_at, created_user_id, updated_at, updated_user_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_ITEM = """
            INSERT INTO order_items (order_id, brand_id, item_type, sale_product_id, product_id,
                                     channel_product_code, channel_option_code, quantity, unit_price, status,
                                     created_at, created_user_id, updated_at, updated_user_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_HISTORY = """
            INSERT INTO order_status_history (order_id, target_type, target_id, previous_status, current_status,
                                              changed_by, created_at)
            VALUES (?, ?, ?, NULL, ?, ?, ?)
            """;
    private static final String SELECT_IDS = "SELECT id, order_no FROM orders WHERE order_no IN (:orderNos)";

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final TransactionTemplate chunkTransaction;

    OrderBatchWriter(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate,
                     PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.chunkTransaction = new TransactionTemplate(transactionManager);
        this.chunkTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @return drafts와 같은 순서의 결과
     */
    List<OrderRegistrationResult> write(List<OrderDraft> drafts, Long userId) {
        List<OrderRegistrationResult> results = new ArrayList<>(drafts.size());
        for (List<OrderDraft> chunk : InClause.partition(drafts, CHUNK_SIZE)) {
            try {
                chunkTransaction.executeWithoutResult(status -> insert(chunk, userId));
                chunk.forEach(d -> results.add(registered(d)));
            } catch (DataAccessException e) {
                log.warn("주문 묶음 등록 실패, 주문 단위로 재시도: size={}, cause={}", chunk.size(),
                        NestedExceptionUtils.getMostSpecificCause(e).getMessage());
                chunk.forEach(d -> results.add(insertOne(d, userId)));
            }
        }
        return results;
    }

    private OrderRegistrationResult insertOne(OrderDraft draft, Long userId) {
        try {
            chunkTransaction.executeWithoutResult(status -> insert(List.of(draft), userId));
            return registered(draft);
        } catch (DuplicateKeyException e) {
            // 사전 중복 체크 이후 다른 업로드·수집이 같은 주문을 먼저 넣은 경우
            if (NestedExceptionUtils.getMostSpecificCause(e).getMessage().contains(CHANNEL_ORDER_UK)) {
                return OrderRegistrationResult.duplicate();
            }
            return failed(draft, e);
        } catch (DataAccessException e) {
            return failed(draft, e);
        }
    }

    private void insert(List<OrderDraft> chunk, Long userId) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        jdbcTemplate.batchUpdate(INSERT_ORDER, chunk.stream().map(d -> orderRow(d.order(), now, userId)).toList());

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

    private static Object[] orderRow(Order o, LocalDateTime now, Long userId) {
        return new Object[]{o.getOrderNo(), o.getSalesChannelId(), o.getChannelOrderNo(), o.getCompanyId(),
                o.getStatus().name(), o.isMappingPending(), o.getTotalItemAmount(), o.getPaidAmount(), o.getCurrency(), o.getOrdererName(),
                o.getReceiverName(), o.getReceiverPhone(), o.getReceiverZipcode(), o.getReceiverAddress(),
                o.getDeliveryMemo(), o.getMarketMemo(), o.getOrderedAt(), now, userId, now, userId};
    }

    private static Object[] itemRow(Long orderId, OrderItem i, LocalDateTime now, Long userId) {
        return new Object[]{orderId, i.getBrandId(), i.getItemType().name(), i.getSaleProductId(), i.getProductId(),
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
