package com.crossborder.oms.service.shipment;

import com.crossborder.common.entity.order.Order;
import com.crossborder.common.entity.order.OrderStatus;
import com.crossborder.common.entity.order.OrderStatusHistory;
import com.crossborder.common.entity.order.Shipment;
import com.crossborder.common.entity.order.ShipmentItem;
import com.crossborder.common.entity.order.ShipmentStatus;
import com.crossborder.oms.dto.shipment.BulkResult;
import com.crossborder.oms.dto.shipment.ShipmentResponse;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.oms.repository.OrderRepository;
import com.crossborder.oms.repository.OrderStatusHistoryRepository;
import com.crossborder.oms.repository.ShipmentItemRepository;
import com.crossborder.oms.repository.ShipmentRepository;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.security.scope.ScopeCheck;
import com.crossborder.oms.security.scope.ScopeId;
import com.crossborder.oms.security.scope.ScopePolicy;
import com.crossborder.oms.security.scope.ScopeTarget;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 출고지시 (CREATED → INSTRUCTED). oms 안에서 상태 전이와 이력까지만 한다 — cbt 출고 접수 호출은 출고 연동 Phase 몫.
 * <p>
 * 주문 행 락으로 취소 판정과 직렬화한다: 취소는 주문 행을 잠근 뒤 "INSTRUCTED 이상 배정분 제외"로 판정하므로,
 * 지시와 취소가 겹쳐도 어느 한쪽이 먼저 커밋된 상태를 다른 쪽이 본다.
 * 락 순서는 전 경로 공통(제품 락 → 주문 행 락)이고, 지시는 재고를 바꾸지 않아 주문 행 락만 잡는다.
 * 같은 트랜잭션에서 제품 락이 필요해지는 변경을 할 때는 제품 락을 먼저 잡도록 순서를 다시 맞출 것.
 * <p>
 * 첫 지시 시점에 주문 상태 SHIPPING (Order.startShipping). 지시 이후 회차의 항목·수량·금액은 바뀌지 않는다
 * (취소는 INSTRUCTED 배정분을 건드리지 않고, 재분리는 거부된다).
 */
@Service
public class ShipmentInstructService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentItemRepository shipmentItemRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final ScopePolicy scopePolicy;
    private final ShipmentResponses responses;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ShipmentInstructService(ShipmentRepository shipmentRepository, ShipmentItemRepository shipmentItemRepository,
                                   OrderRepository orderRepository,
                                   OrderStatusHistoryRepository orderStatusHistoryRepository, ScopePolicy scopePolicy,
                                   ShipmentResponses responses, PlatformTransactionManager transactionManager,
                                   Clock clock) {
        this.shipmentRepository = shipmentRepository;
        this.shipmentItemRepository = shipmentItemRepository;
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.scopePolicy = scopePolicy;
        this.responses = responses;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @throws ConflictException CREATED가 아닌 회차
     */
    @ScopeCheck(ScopeTarget.SHIPMENT)
    @Transactional
    public ShipmentResponse instruct(@ScopeId Long shipmentId, AuthenticatedUser user) {
        return doInstruct(shipmentId, user);
    }

    /** 회차마다 독립 트랜잭션 */
    public BulkResult<ShipmentResponse> instructAll(List<Long> shipmentIds, AuthenticatedUser user) {
        ShipmentResponses.requireBulkSize(shipmentIds);
        List<BulkResult.Entry<ShipmentResponse>> results = new ArrayList<>();
        for (Long shipmentId : shipmentIds) {
            try {
                ShipmentResponse response = transaction.execute(status -> {
                    scopePolicy.requireShipment(shipmentId, user);
                    return doInstruct(shipmentId, user);
                });
                results.add(new BulkResult.Entry<>(shipmentId, true, response, null, null));
            } catch (RuntimeException e) {
                results.add(ShipmentResponses.failure(shipmentId, e));
            }
        }
        return BulkResult.of(results);
    }

    private ShipmentResponse doInstruct(Long shipmentId, AuthenticatedUser user) {
        Long orderId = shipmentRepository.findOrderIdById(shipmentId)
                .orElseThrow(() -> new NotFoundException("회차를 찾을 수 없습니다. shipmentId=" + shipmentId));
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다. orderId=" + orderId));
        // 회차 엔티티는 주문 행 잠금 이후에 처음 읽는다 → 판정은 최신 상태 기준
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new NotFoundException("회차를 찾을 수 없습니다. shipmentId=" + shipmentId));
        if (shipment.getStatus() != ShipmentStatus.CREATED) {
            throw new ConflictException("출고지시할 수 없는 회차 상태입니다. shipmentNo=" + shipment.getShipmentNo()
                    + ", status=" + shipment.getStatus());
        }
        Long userId = user.userId();
        shipment.instruct(userId, LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
        orderStatusHistoryRepository.save(OrderStatusHistory.ofShipment(shipment, ShipmentStatus.CREATED, userId));
        if (order.getStatus() != OrderStatus.SHIPPING) {
            OrderStatus previous = order.getStatus();
            order.startShipping();
            orderStatusHistoryRepository.save(OrderStatusHistory.ofOrder(order, previous, userId));
        }
        List<ShipmentItem> items = shipmentItemRepository.findByShipmentIdIn(List.of(shipmentId));
        return responses.of(shipment, items);
    }
}
