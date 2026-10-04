package com.crossborder.oms.service.shipment;

import com.crossborder.common.entity.order.Order;
import com.crossborder.common.entity.order.OrderItem;
import com.crossborder.common.entity.order.OrderStatus;
import com.crossborder.common.entity.order.OrderStatusHistory;
import com.crossborder.common.entity.order.Shipment;
import com.crossborder.common.entity.order.ShipmentItem;
import com.crossborder.common.entity.order.ShipmentStatus;
import com.crossborder.common.entity.product.CustomsCategory;
import com.crossborder.common.entity.product.Product;
import com.crossborder.common.entity.product.SaleProductItem;
import com.crossborder.oms.dto.shipment.BulkResult;
import com.crossborder.oms.dto.shipment.ShipmentResponse;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.ForbiddenException;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.oms.repository.CustomsCategoryRepository;
import com.crossborder.oms.repository.OrderItemRepository;
import com.crossborder.oms.repository.OrderRepository;
import com.crossborder.oms.repository.OrderStatusHistoryRepository;
import com.crossborder.oms.repository.ProductRepository;
import com.crossborder.oms.repository.ShipmentItemRepository;
import com.crossborder.oms.repository.ShipmentRepository;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.security.scope.ScopeCheck;
import com.crossborder.oms.security.scope.ScopeId;
import com.crossborder.oms.security.scope.ScopePolicy;
import com.crossborder.oms.security.scope.ScopeTarget;
import com.crossborder.oms.service.stock.StockAllocator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 분리 (출고 회차 생성). 규칙은 ShipmentPlanner — 브랜드 분리 → 통관 분류 한도 순차 배분.
 * <p>
 * 대상: 매핑 완료 + PAID·PARTIAL_CANCELED + 유효 항목 1개 이상. 스코프: 주문 스코프 + 유효 항목이 전부 사용자의 브랜드 범위
 * (멀티브랜드 주문을 BRAND_STAFF가 분리하면 다른 브랜드 회차가 생기므로 막는다).
 * 재분리: CREATED 회차만 있으면 전부 CANCELED 처리 후 다시 분리 (부분취소 후 재분리가 주 용도).
 * INSTRUCTED 이상 회차가 하나라도 있으면 거부. 회차 번호는 취소된 회차를 포함한 최대 번호 다음부터 (shipment_no 유니크).
 * <p>
 * 주문 행을 잠그고(PESSIMISTIC_WRITE) 처리한다 — 취소·출고지시와 직렬화. 재고는 바뀌지 않으므로 제품 락은 잡지 않는다.
 * 분리 시점에 판매상품 구성으로 제품 단위 전개를 한다 (지연 전개, 할당과 같은 구성 — 구성 불변 전제).
 */
@Service
public class OrderSplitService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ShipmentRepository shipmentRepository;
    private final ShipmentItemRepository shipmentItemRepository;
    private final ProductRepository productRepository;
    private final CustomsCategoryRepository customsCategoryRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final StockAllocator stockAllocator;
    private final ScopePolicy scopePolicy;
    private final ShipmentResponses responses;
    private final TransactionTemplate transaction;

    public OrderSplitService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                             ShipmentRepository shipmentRepository, ShipmentItemRepository shipmentItemRepository,
                             ProductRepository productRepository, CustomsCategoryRepository customsCategoryRepository,
                             OrderStatusHistoryRepository orderStatusHistoryRepository, StockAllocator stockAllocator,
                             ScopePolicy scopePolicy, ShipmentResponses responses,
                             PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.shipmentRepository = shipmentRepository;
        this.shipmentItemRepository = shipmentItemRepository;
        this.productRepository = productRepository;
        this.customsCategoryRepository = customsCategoryRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.stockAllocator = stockAllocator;
        this.scopePolicy = scopePolicy;
        this.responses = responses;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * @throws ConflictException  분리 대상 아님 / 출고지시된 회차 있음 / 분할 불가(단일 상품이 통관 한도 초과)
     * @throws ForbiddenException 유효 항목 중 사용자 브랜드 범위 밖 항목이 있음
     */
    @ScopeCheck(ScopeTarget.ORDER)
    @Transactional
    public List<ShipmentResponse> split(@ScopeId Long orderId, AuthenticatedUser user) {
        return doSplit(orderId, user);
    }

    /** 주문마다 독립 트랜잭션. 한 주문의 실패가 다른 주문에 영향을 주지 않는다 */
    public BulkResult<List<ShipmentResponse>> splitAll(List<Long> orderIds, AuthenticatedUser user) {
        ShipmentResponses.requireBulkSize(orderIds);
        List<BulkResult.Entry<List<ShipmentResponse>>> results = new ArrayList<>();
        for (Long orderId : orderIds) {
            try {
                List<ShipmentResponse> shipments = transaction.execute(status -> {
                    scopePolicy.requireOrder(orderId, user);
                    return doSplit(orderId, user);
                });
                results.add(new BulkResult.Entry<>(orderId, true, shipments, null, null));
            } catch (RuntimeException e) {
                results.add(ShipmentResponses.failure(orderId, e));
            }
        }
        return BulkResult.of(results);
    }

    /** 주문의 회차 (취소된 회차 포함, 회차 번호 순) */
    @ScopeCheck(ScopeTarget.ORDER)
    @Transactional(readOnly = true)
    public List<ShipmentResponse> shipments(@ScopeId Long orderId) {
        return responses.of(shipmentRepository.findByOrderIdOrderByRoundNo(orderId));
    }

    private List<ShipmentResponse> doSplit(Long orderId, AuthenticatedUser user) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다. orderId=" + orderId));
        if (order.isMappingPending()) {
            throw new ConflictException("매핑안됨 항목이 있는 주문은 분리할 수 없습니다. orderId=" + orderId);
        }
        // 재분리 거부 사유를 상태 사유보다 먼저 — 지시 후 주문은 SHIPPING이라 상태 검사가 먼저면 원인이 가려진다
        List<Shipment> existing = shipmentRepository.findByOrderIdOrderByRoundNo(orderId);
        List<String> instructed = existing.stream().filter(Shipment::isInstructed).map(Shipment::getShipmentNo).toList();
        if (!instructed.isEmpty()) {
            throw new ConflictException("출고지시된 회차가 있어 재분리할 수 없습니다: " + String.join(", ", instructed));
        }
        if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.PARTIAL_CANCELED) {
            throw new ConflictException("분리할 수 없는 주문 상태입니다. orderId=" + orderId + ", status=" + order.getStatus());
        }
        List<OrderItem> items = orderItemRepository.findByOrderIdIn(List.of(orderId)).stream()
                .filter(OrderItem::isOrdered).toList();
        if (items.isEmpty()) {
            throw new ConflictException("유효 항목이 없는 주문은 분리할 수 없습니다. orderId=" + orderId);
        }
        List<String> forbidden = items.stream().filter(i -> !scopePolicy.canAccessBrand(i.getBrandId(), user))
                .map(i -> "orderItemId=" + i.getId() + "(brandId=" + i.getBrandId() + ")").toList();
        if (!forbidden.isEmpty()) {
            throw new ForbiddenException("다른 브랜드 항목이 있는 주문은 분리할 수 없습니다: " + String.join(", ", forbidden));
        }

        ShipmentPlanner.Result plan = planner(items).plan(items);
        if (plan.failed()) {
            throw new ConflictException(plan.failure());
        }

        Long userId = user.userId();
        for (Shipment shipment : existing) {
            if (shipment.getStatus() == ShipmentStatus.CREATED) {
                shipment.cancel();
                orderStatusHistoryRepository.save(OrderStatusHistory.ofShipment(shipment, ShipmentStatus.CREATED, userId));
            }
        }
        int roundNo = existing.stream().mapToInt(Shipment::getRoundNo).max().orElse(0);
        List<ShipmentResponse> created = new ArrayList<>();
        for (ShipmentPlanner.PlannedShipment planned : plan.shipments()) {
            Shipment shipment = shipmentRepository.save(Shipment.create(order, planned.brandId(), ++roundNo,
                    planned.splitReason(), planned.totalAmount()));
            List<ShipmentItem> shipmentItems = planned.lines().stream()
                    .map(line -> ShipmentItem.create(shipment.getId(), line.item(), line.quantity()))
                    .toList();
            shipmentItemRepository.saveAll(shipmentItems);
            orderStatusHistoryRepository.save(OrderStatusHistory.ofShipment(shipment, null, userId));
            created.add(responses.of(shipment, shipmentItems));
        }
        return created;
    }

    private ShipmentPlanner planner(List<OrderItem> items) {
        Map<Long, List<SaleProductItem>> compositions = stockAllocator.loadCompositions(items.stream()
                .map(OrderItem::getSaleProductId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Set<Long> productIds = new HashSet<>();
        items.stream().map(OrderItem::getProductId).filter(Objects::nonNull).forEach(productIds::add);
        compositions.values().forEach(components -> components.forEach(c -> productIds.add(c.getProductId())));
        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Set<Long> categoryIds = products.values().stream().map(Product::getCustomsCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, CustomsCategory> categories = customsCategoryRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(CustomsCategory::getId, Function.identity()));
        return new ShipmentPlanner(compositions, products, categories);
    }
}
