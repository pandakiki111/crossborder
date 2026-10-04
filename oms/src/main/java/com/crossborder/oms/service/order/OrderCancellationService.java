package com.crossborder.oms.service.order;

import com.crossborder.common.entity.order.Order;
import com.crossborder.common.entity.order.OrderItem;
import com.crossborder.common.entity.order.OrderItemType;
import com.crossborder.common.entity.order.OrderStatus;
import com.crossborder.common.entity.order.OrderStatusHistory;
import com.crossborder.common.entity.order.Shipment;
import com.crossborder.common.entity.order.ShipmentItem;
import com.crossborder.common.entity.order.ShipmentStatus;
import com.crossborder.common.entity.product.SaleProductItem;
import com.crossborder.infra.lock.DistributedLockManager;
import com.crossborder.oms.dto.order.CancellationItemRequest;
import com.crossborder.oms.dto.order.CancellationResponse;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.ForbiddenException;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.oms.repository.OrderItemRepository;
import com.crossborder.oms.repository.OrderRepository;
import com.crossborder.oms.repository.OrderStatusHistoryRepository;
import com.crossborder.oms.repository.ShipmentItemRepository;
import com.crossborder.oms.repository.ShipmentRepository;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.security.scope.ScopeCheck;
import com.crossborder.oms.security.scope.ScopeId;
import com.crossborder.oms.security.scope.ScopePolicy;
import com.crossborder.oms.security.scope.ScopeTarget;
import com.crossborder.oms.service.stock.StockAllocator;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자 수동 취소 (항목 단위, 수량 부분취소). 주문 전체 취소는 전 항목 지정의 합성이다.
 * <p>
 * 요청은 원자적이다 — 한 항목이라도 거부되면 전체를 거부하고 아무것도 바꾸지 않는다 (부분 성공 없음).
 * 판정 순서: 요청 형식(400) → 항목 브랜드 스코프(403) → 상태·수량(409). 같은 단계의 사유는 모두 모아 돌려준다.
 * <p>
 * 취소 가능 수량 = 유효 수량 - INSTRUCTED 이상 회차에 배정된 수량 (미배정 + CREATED 회차 배정분까지 취소 가능).
 * 주문 행을 잠근 뒤 같은 트랜잭션에서 판정 직후 처리하므로, 판정에 쓴 회차 배정 수량과 실제 감량이 어긋나지 않는다.
 * <p>
 * 처리 (한 트랜잭션):
 * <ol>
 *   <li>전량이면 cancel(), 일부면 splitCanceled(행 분할). 사은품은 전체 취소만 (엔티티 규칙)</li>
 *   <li>할당 해제: 할당 완료 주문이면 취소 수량의 전개분만큼 allocated 감소 (제품 락)</li>
 *   <li>CREATED 회차 정리: 미배정 수량부터 소진하고 모자라는 만큼만 CREATED 회차에서 회차 번호 역순으로 감량.
 *       회차가 비면 CANCELED</li>
 *   <li>주문 상태: 전 항목 취소면 CANCELED, 일부면 cancelPartially (PAID → PARTIAL_CANCELED, SHIPPING은 유지)</li>
 *   <li>상태 이력: ORDER 변경 + 비어서 취소된 SHIPMENT</li>
 * </ol>
 * 매핑안됨 항목을 취소해 남은 매핑안됨 항목이 없어지면 매핑 완료로 전이하고 그때 주문 전체를 할당한다.
 * <p>
 * 락 순서: 제품 분산 락 → 주문 행 락 (시딩·소급 할당과 같은 순서라 교착이 없다). 제품 락 키는 주문 행을 잠그기 전에
 * 주문 전체 항목의 전개로 정하고, 잠근 뒤 실제로 필요한 제품이 그 범위를 벗어나면(그 사이 매핑 확정 등) 409로 거부한다.
 * 본품 취소 시 사은품 자동 연동 취소는 하지 않는다 (운영자가 사은품 행을 직접 선택).
 */
@Service
public class OrderCancellationService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ShipmentRepository shipmentRepository;
    private final ShipmentItemRepository shipmentItemRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final ScopePolicy scopePolicy;
    private final StockAllocator stockAllocator;
    private final DistributedLockManager lockManager;
    private final Clock clock;

    public OrderCancellationService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                                    ShipmentRepository shipmentRepository, ShipmentItemRepository shipmentItemRepository,
                                    OrderStatusHistoryRepository orderStatusHistoryRepository, ScopePolicy scopePolicy,
                                    StockAllocator stockAllocator, DistributedLockManager lockManager, Clock clock) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.shipmentRepository = shipmentRepository;
        this.shipmentItemRepository = shipmentItemRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.scopePolicy = scopePolicy;
        this.stockAllocator = stockAllocator;
        this.lockManager = lockManager;
        this.clock = clock;
    }

    /** 항목별 판정 결과 */
    private record Plan(OrderItem item, int quantity, int unassigned, List<ShipmentItem> createdAssignments) {
    }

    @ScopeCheck(ScopeTarget.ORDER)
    @Transactional
    public CancellationResponse cancel(@ScopeId Long orderId, List<CancellationItemRequest> requests,
                                       AuthenticatedUser user) {
        validateRequestShape(requests);
        Set<Long> lockedProducts = productsOf(orderId);
        lockManager.lockUntilTransactionEnd(StockAllocator.lockKeys(lockedProducts));
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다. orderId=" + orderId));
        // 항목은 행 잠금 이후에 처음 엔티티로 읽는다 → 판정은 최신 상태 기준
        Map<Long, OrderItem> orderItems = new LinkedHashMap<>();
        orderItemRepository.findByOrderIdIn(List.of(orderId)).forEach(item -> orderItems.put(item.getId(), item));

        List<String> notInOrder = requests.stream()
                .filter(r -> !orderItems.containsKey(r.orderItemId()))
                .map(r -> "orderItemId=" + r.orderItemId() + ": 이 주문의 항목이 아님")
                .toList();
        reject(notInOrder, InvalidRequestException::new);

        List<String> forbidden = requests.stream()
                .map(r -> orderItems.get(r.orderItemId()))
                .filter(item -> !scopePolicy.canAccessBrand(item.getBrandId(), user))
                .map(item -> "orderItemId=" + item.getId() + ": 해당 브랜드에 대한 권한이 없음 (brandId=" + item.getBrandId() + ")")
                .toList();
        reject(forbidden, ForbiddenException::new);

        List<Plan> plans = judge(requests, orderItems);

        Long userId = user.userId();
        OrderStatus previousStatus = order.getStatus();
        boolean wasAllocated = order.isAllocated();
        Map<Long, List<SaleProductItem>> compositions = stockAllocator.loadCompositions(orderItems.values().stream()
                .map(OrderItem::getSaleProductId).filter(Objects::nonNull).collect(Collectors.toSet()));

        List<CancellationResponse.CanceledItem> canceledItems = new ArrayList<>();
        Map<Long, Integer> deallocation = new TreeMap<>();
        Set<Long> touchedShipmentIds = new HashSet<>();
        for (Plan plan : plans) {
            OrderItem item = plan.item();
            int quantity = plan.quantity();
            if (wasAllocated) {
                StockAllocator.expand(item, quantity, compositions)
                        .forEach((productId, qty) -> deallocation.merge(productId, qty, Integer::sum));
            }
            Long canceledRowId;
            if (quantity == item.getQuantity()) {
                item.cancel();
                canceledRowId = item.getId();
            } else {
                canceledRowId = orderItemRepository.save(item.splitCanceled(quantity)).getId();
            }
            touchedShipmentIds.addAll(reduceCreatedAssignments(plan));
            canceledItems.add(new CancellationResponse.CanceledItem(item.getId(), quantity, canceledRowId));
        }
        shipmentItemRepository.flush();

        List<Long> canceledShipmentIds = cancelEmptyShipments(touchedShipmentIds, userId);
        Map<Long, Integer> allocation = completeMappingIfResolved(order, orderItems.values(), compositions);

        Set<Long> required = new TreeSet<>(deallocation.keySet());
        required.addAll(allocation.keySet());
        if (!lockedProducts.containsAll(required)) {
            throw new ConflictException("처리 중 주문 항목이 바뀌었습니다 (매핑 확정 등). 다시 시도하세요. orderId=" + orderId);
        }
        stockAllocator.decrease(deallocation);
        stockAllocator.increase(allocation);

        if (orderItems.values().stream().noneMatch(OrderItem::isOrdered)) {
            order.cancel();
        } else {
            order.cancelPartially();
        }
        if (order.getStatus() != previousStatus) {
            orderStatusHistoryRepository.save(OrderStatusHistory.ofOrder(order, previousStatus, userId));
        }
        return new CancellationResponse(order.getId(), order.getStatus(), canceledItems, canceledShipmentIds);
    }

    private static void validateRequestShape(List<CancellationItemRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new InvalidRequestException("취소할 항목이 없습니다.");
        }
        List<String> reasons = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (CancellationItemRequest r : requests) {
            if (r == null || r.orderItemId() == null) {
                reasons.add("orderItemId가 없음");
                continue;
            }
            if (!seen.add(r.orderItemId())) {
                reasons.add("orderItemId=" + r.orderItemId() + ": 같은 항목이 중복 지정됨");
            }
            if (r.quantity() == null || r.quantity() <= 0) {
                reasons.add("orderItemId=" + r.orderItemId() + ": 수량은 1 이상이어야 함");
            }
        }
        reject(reasons, InvalidRequestException::new);
    }

    /** 상태·수량 판정. 회차 배정 수량은 주문 행 잠금 이후에 읽는다 */
    private List<Plan> judge(List<CancellationItemRequest> requests, Map<Long, OrderItem> orderItems) {
        List<Long> requestedIds = requests.stream().map(CancellationItemRequest::orderItemId).toList();
        List<ShipmentItem> assignments = shipmentItemRepository.findByOrderItemIdIn(requestedIds);
        Map<Long, Shipment> shipments = shipmentRepository.findAllById(
                        assignments.stream().map(ShipmentItem::getShipmentId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Shipment::getId, Function.identity()));
        Map<Long, List<ShipmentItem>> byItem = assignments.stream()
                .collect(Collectors.groupingBy(ShipmentItem::getOrderItemId));

        List<Plan> plans = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        for (CancellationItemRequest request : requests) {
            OrderItem item = orderItems.get(request.orderItemId());
            int quantity = request.quantity();
            if (!item.isOrdered()) {
                conflicts.add("orderItemId=" + item.getId() + ": 이미 취소된 항목");
                continue;
            }
            int instructed = 0;
            int createdAssigned = 0;
            List<ShipmentItem> created = new ArrayList<>();
            for (ShipmentItem assignment : byItem.getOrDefault(item.getId(), List.of())) {
                ShipmentStatus status = shipments.get(assignment.getShipmentId()).getStatus();
                if (status == ShipmentStatus.CREATED) {
                    createdAssigned += assignment.getQuantity();
                    created.add(assignment);
                } else if (status != ShipmentStatus.CANCELED) {
                    instructed += assignment.getQuantity();
                }
            }
            int cancelable = item.getQuantity() - instructed;
            if (quantity > cancelable) {
                conflicts.add("orderItemId=" + item.getId() + ": 취소 가능 " + cancelable + "개, 요청 " + quantity
                        + "개 (출고지시된 회차 배정 " + instructed + "개)");
                continue;
            }
            if (item.getItemType() == OrderItemType.GIFT_PRODUCT && quantity < item.getQuantity()) {
                conflicts.add("orderItemId=" + item.getId() + ": 사은품은 수량 부분취소 없이 전체 취소만 가능");
                continue;
            }
            // 회차 번호 역순 — 늦게 만든 회차부터 비운다
            created.sort(Comparator.comparingInt(
                    (ShipmentItem a) -> shipments.get(a.getShipmentId()).getRoundNo()).reversed());
            plans.add(new Plan(item, quantity, item.getQuantity() - instructed - createdAssigned, created));
        }
        reject(conflicts, ConflictException::new);
        return plans;
    }

    /**
     * 미배정 수량부터 소진하고 모자라는 만큼만 CREATED 회차 배정에서 감량한다. 항목 수량을 먼저 줄인 뒤 호출한다.
     *
     * @return 감량한 회차 id
     */
    private List<Long> reduceCreatedAssignments(Plan plan) {
        int remaining = Math.max(0, plan.quantity() - plan.unassigned());
        List<Long> touched = new ArrayList<>();
        for (ShipmentItem assignment : plan.createdAssignments()) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(assignment.getQuantity(), remaining);
            remaining -= take;
            touched.add(assignment.getShipmentId());
            if (take == assignment.getQuantity()) {
                shipmentItemRepository.delete(assignment);
            } else {
                assignment.changeQuantity(plan.item(), assignment.getQuantity() - take);
            }
        }
        return touched;
    }

    private List<Long> cancelEmptyShipments(Set<Long> shipmentIds, Long userId) {
        List<Long> canceled = new ArrayList<>();
        for (Shipment shipment : shipmentRepository.findAllById(shipmentIds)) {
            if (!shipmentItemRepository.existsByShipmentId(shipment.getId())) {
                shipment.cancel();
                orderStatusHistoryRepository.save(OrderStatusHistory.ofShipment(shipment, ShipmentStatus.CREATED, userId));
                canceled.add(shipment.getId());
            }
        }
        canceled.sort(Long::compareTo);
        return canceled;
    }

    /**
     * 매핑안됨 항목을 취소해 남은 매핑안됨 항목이 없어졌으면 매핑 완료로 전이하고 주문 전체 할당 수량을 돌려준다.
     * 전 항목이 취소돼 남은 유효 항목이 없으면 할당할 것이 없으므로 allocated_at을 기록하지 않는다
     * (취소 주문은 소급 대상도 아니다 — allocated_at IS NULL AND status NOT IN ('CANCELED')).
     */
    private Map<Long, Integer> completeMappingIfResolved(Order order, Collection<OrderItem> items,
                                                         Map<Long, List<SaleProductItem>> compositions) {
        if (!order.isMappingPending() || items.stream().anyMatch(i -> i.isOrdered() && !i.isMapped())) {
            return Map.of();
        }
        order.completeMapping();
        if (items.stream().noneMatch(OrderItem::isOrdered)) {
            return Map.of();
        }
        order.markAllocated(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
        return StockAllocator.expand(items, compositions);
    }

    /**
     * 확정된(전개 가능한) 항목의 제품 전체 — 제품 락 키 산정용 상한. 항목 엔티티를 영속성 컨텍스트에 올리지 않도록
     * id만 조회한다 (올리면 행 잠금 이후 조회도 잠금 전 상태를 돌려준다).
     */
    private Set<Long> productsOf(Long orderId) {
        Set<Long> products = new TreeSet<>(orderItemRepository.findGiftProductIds(orderId));
        stockAllocator.loadCompositions(orderItemRepository.findSaleProductIds(orderId)).values()
                .forEach(components -> components.forEach(c -> products.add(c.getProductId())));
        return products;
    }

    private static void reject(List<String> reasons, Function<String, RuntimeException> exception) {
        if (!reasons.isEmpty()) {
            throw exception.apply("취소할 수 없는 항목이 있어 요청 전체를 거부합니다: " + String.join(" / ", reasons));
        }
    }
}
