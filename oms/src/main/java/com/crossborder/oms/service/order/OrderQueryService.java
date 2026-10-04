package com.crossborder.oms.service.order;

import com.crossborder.oms.dto.order.OrderDetailResponse;
import com.crossborder.oms.dto.order.OrderSearchCondition;
import com.crossborder.oms.dto.order.OrderSummaryResponse;
import com.crossborder.oms.repository.OrderQueryRepository;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.security.scope.ScopeCheck;
import com.crossborder.oms.security.scope.ScopeId;
import com.crossborder.oms.security.scope.ScopeTarget;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 조회 (목록·상세).
 * <p>
 * 조회 스코프: ADMIN 전체 / WORKER 불가 / COMPANY_STAFF는 orders.company_id / BRAND_STAFF는 order_items.brand_id 기준.
 */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderQueryRepository orderQueryRepository;

    public OrderQueryService(OrderQueryRepository orderQueryRepository) {
        this.orderQueryRepository = orderQueryRepository;
    }

    /**
     * 매핑안됨 주문은 상태(PAID·PARTIAL_CANCELED 등)와 별개로 mappingPending=true, unmappedItemCount > 0 으로 나온다.
     */
    public Page<OrderSummaryResponse> search(OrderSearchCondition condition, Pageable pageable,
                                             AuthenticatedUser user) {
        return orderQueryRepository.search(condition, pageable, user);
    }

    /**
     * TODO: 항목(판매상품명/제품명 조인, 매핑안됨 항목은 채널 코드) + 회차·회차항목
     */
    @ScopeCheck(ScopeTarget.ORDER)
    public OrderDetailResponse getDetail(@ScopeId Long orderId, AuthenticatedUser user) {
        throw new UnsupportedOperationException("주문 상세 미구현");
    }
}
