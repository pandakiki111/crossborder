package com.crossborder.oms.security.scope;

import com.crossborder.common.entity.order.Order;
import com.crossborder.common.entity.order.Shipment;
import com.crossborder.common.entity.organization.Brand;
import com.crossborder.oms.exception.ForbiddenException;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.oms.repository.BrandRepository;
import com.crossborder.oms.repository.OrderItemRepository;
import com.crossborder.oms.repository.OrderRepository;
import com.crossborder.oms.repository.ShipmentRepository;
import com.crossborder.oms.security.AuthenticatedUser;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * role별 소속 스코프 규칙의 단일 구현. 기준은 항상 principal의 소속(companyId·brandId)이고,
 * 요청이 보낸 회사·브랜드 선언은 믿지 않는다.
 * <ul>
 *   <li>브랜드: ADMIN 전체 / COMPANY_STAFF 자기 회사 브랜드 / BRAND_STAFF 자기 브랜드 / WORKER 불가</li>
 *   <li>주문: ADMIN 전체 / COMPANY_STAFF 자기 회사 주문 / BRAND_STAFF 자기 브랜드 항목이 있는 주문 / WORKER 불가</li>
 *   <li>회차: 회차 브랜드에 브랜드 규칙을 적용 (한 회차 = 단일 브랜드)</li>
 * </ul>
 * WORKER는 cbt 전용 사용자라 SecurityConfig가 oms 전 API에서 먼저 막는다. 여기서도 불가로 두는 것은 이중 방어.
 * 존재 확인 → 스코프 확인 순 (없음 404 / 권한 없음 403). 존재를 숨기는 404 위장은 하지 않는다.
 * <p>
 * 주문 목록 조회의 스코프 조건(OrderQueryRepository.scope)은 같은 규칙을 쿼리 조건으로 옮긴 것이다 — 규칙을 바꾸면 함께 바꿀 것.
 */
@Component
public class ScopePolicy {

    private final BrandRepository brandRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ShipmentRepository shipmentRepository;

    public ScopePolicy(BrandRepository brandRepository, OrderRepository orderRepository,
                       OrderItemRepository orderItemRepository, ShipmentRepository shipmentRepository) {
        this.brandRepository = brandRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.shipmentRepository = shipmentRepository;
    }

    /**
     * @throws NotFoundException  브랜드가 없음
     * @throws ForbiddenException 스코프 밖
     */
    public Brand requireBrand(Long brandId, AuthenticatedUser user) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new NotFoundException("브랜드를 찾을 수 없습니다. brandId=" + brandId));
        if (!canAccess(brand, user)) {
            throw new ForbiddenException("해당 브랜드에 대한 권한이 없습니다. brandId=" + brandId);
        }
        return brand;
    }

    /**
     * 엔티티에서 꺼낸 brandId용 (매핑·판매상품 등). COMPANY_STAFF만 브랜드를 조회하고, 없는 브랜드는 false.
     */
    public boolean canAccessBrand(Long brandId, AuthenticatedUser user) {
        return switch (user.role()) {
            case ADMIN -> true;
            case WORKER -> false;
            case BRAND_STAFF -> Objects.equals(brandId, user.brandId());
            case COMPANY_STAFF -> brandRepository.findById(brandId)
                    .map(brand -> canAccess(brand, user))
                    .orElse(false);
        };
    }

    /**
     * @throws NotFoundException  주문이 없음
     * @throws ForbiddenException 스코프 밖
     */
    public Order requireOrder(Long orderId, AuthenticatedUser user) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다. orderId=" + orderId));
        if (!canAccess(order, user)) {
            throw new ForbiddenException("해당 주문에 대한 권한이 없습니다. orderId=" + orderId);
        }
        return order;
    }

    /**
     * @throws NotFoundException  회차가 없음
     * @throws ForbiddenException 회차 브랜드가 스코프 밖
     */
    public Shipment requireShipment(Long shipmentId, AuthenticatedUser user) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new NotFoundException("회차를 찾을 수 없습니다. shipmentId=" + shipmentId));
        if (!canAccessBrand(shipment.getBrandId(), user)) {
            throw new ForbiddenException("해당 회차에 대한 권한이 없습니다. shipmentId=" + shipmentId);
        }
        return shipment;
    }

    void check(ScopeTarget target, Long id, AuthenticatedUser user) {
        switch (target) {
            case BRAND -> requireBrand(id, user);
            case ORDER -> requireOrder(id, user);
            case SHIPMENT -> requireShipment(id, user);
        }
    }

    private static boolean canAccess(Brand brand, AuthenticatedUser user) {
        return switch (user.role()) {
            case ADMIN -> true;
            case WORKER -> false;
            case COMPANY_STAFF -> Objects.equals(brand.getCompanyId(), user.companyId());
            case BRAND_STAFF -> Objects.equals(brand.getId(), user.brandId());
        };
    }

    /** 한 주문의 회사는 단일이라 COMPANY_STAFF는 주문의 company_id로, BRAND_STAFF는 항목 브랜드로 판정 (멀티브랜드 주문) */
    private boolean canAccess(Order order, AuthenticatedUser user) {
        return switch (user.role()) {
            case ADMIN -> true;
            case WORKER -> false;
            case COMPANY_STAFF -> Objects.equals(order.getCompanyId(), user.companyId());
            case BRAND_STAFF -> orderItemRepository.existsByOrderIdAndBrandId(order.getId(), user.brandId());
        };
    }
}
