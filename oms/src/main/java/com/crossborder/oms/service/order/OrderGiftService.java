package com.crossborder.oms.service.order;

import com.crossborder.common.entity.order.Order;
import com.crossborder.common.entity.order.OrderItem;
import com.crossborder.common.entity.order.OrderStatus;
import com.crossborder.common.entity.organization.Brand;
import com.crossborder.common.entity.product.Product;
import com.crossborder.infra.lock.DistributedLockManager;
import com.crossborder.oms.dto.order.GiftAddRequest;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.ForbiddenException;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.oms.repository.BrandRepository;
import com.crossborder.oms.repository.OrderItemRepository;
import com.crossborder.oms.repository.OrderRepository;
import com.crossborder.oms.repository.ProductRepository;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.security.scope.ScopeCheck;
import com.crossborder.oms.security.scope.ScopeId;
import com.crossborder.oms.security.scope.ScopePolicy;
import com.crossborder.oms.security.scope.ScopeTarget;
import com.crossborder.oms.service.stock.StockAllocator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자 수동 증정 (order_items GIFT_PRODUCT, gift_source=MANUAL). 판매상품 구성 고정 사은품과는 별개.
 * 취소는 기존 취소 API(POST /api/orders/{orderId}/cancellations — 사은품은 전체 취소만)로 한다.
 * <p>
 * 대상: 결제완료·부분취소 주문 (출고 진행 중·취소 주문 제외). 제품은 주문과 같은 회사 소속이고 사용자 스코프 안 브랜드여야 한다.
 * 할당된 주문이면 같은 트랜잭션에서 증정분을 할당한다. 락 순서: 제품 락 → 주문 행 락 (취소·할당과 같다).
 */
@Service
public class OrderGiftService {

    private static final Set<OrderStatus> GIFTABLE = Set.of(OrderStatus.PAID, OrderStatus.PARTIAL_CANCELED);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final ScopePolicy scopePolicy;
    private final StockAllocator stockAllocator;
    private final DistributedLockManager lockManager;

    public OrderGiftService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                            ProductRepository productRepository, BrandRepository brandRepository,
                            ScopePolicy scopePolicy, StockAllocator stockAllocator, DistributedLockManager lockManager) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.brandRepository = brandRepository;
        this.scopePolicy = scopePolicy;
        this.stockAllocator = stockAllocator;
        this.lockManager = lockManager;
    }

    /**
     * @return 생성된 주문 항목 id
     * @throws InvalidRequestException 제품 없음·다른 회사 제품
     * @throws ForbiddenException      스코프 밖 브랜드 제품
     * @throws ConflictException       증정할 수 없는 주문 상태
     */
    @ScopeCheck(ScopeTarget.ORDER)
    @Transactional
    public Long addGift(@ScopeId Long orderId, GiftAddRequest request, AuthenticatedUser user) {
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new InvalidRequestException("제품이 없습니다. productId=" + request.productId()));
        if (!scopePolicy.canAccessBrand(product.getBrandId(), user)) {
            throw new ForbiddenException("해당 브랜드 제품에 대한 권한이 없습니다. productId=" + product.getId());
        }
        lockManager.lockUntilTransactionEnd(StockAllocator.lockKeys(List.of(product.getId())));
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다. orderId=" + orderId));
        Brand brand = brandRepository.findById(product.getBrandId()).orElseThrow();
        if (!brand.getCompanyId().equals(order.getCompanyId())) {
            throw new InvalidRequestException("주문과 다른 회사의 제품은 증정할 수 없습니다. productId=" + product.getId());
        }
        if (!GIFTABLE.contains(order.getStatus())) {
            throw new ConflictException("결제완료·부분취소 주문에만 사은품을 추가할 수 있습니다. status=" + order.getStatus());
        }
        OrderItem gift = orderItemRepository.save(OrderItem.ofManualGift(orderId, product, request.quantity()));
        if (order.isAllocated()) {
            stockAllocator.increase(Map.of(product.getId(), request.quantity()));
        }
        return gift.getId();
    }
}
