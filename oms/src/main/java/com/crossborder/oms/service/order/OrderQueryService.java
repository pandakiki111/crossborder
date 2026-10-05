package com.crossborder.oms.service.order;

import com.crossborder.oms.config.OrderSearchProperties;
import com.crossborder.oms.dto.CappedPageResponse;
import com.crossborder.oms.dto.order.OrderDetailResponse;
import com.crossborder.oms.dto.order.OrderSearchCondition;
import com.crossborder.oms.dto.order.OrderSummaryResponse;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.repository.OrderQueryRepository;
import com.crossborder.oms.repository.OrderQueryRepository.Row;
import com.crossborder.oms.repository.OrderSearchCriteria;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.security.scope.ScopeCheck;
import com.crossborder.oms.security.scope.ScopeId;
import com.crossborder.oms.security.scope.ScopeTarget;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 조회 (목록·상세).
 * <p>
 * 조회 스코프: ADMIN 전체 / WORKER 불가 / COMPANY_STAFF는 orders.company_id / BRAND_STAFF는 order_items.brand_id 기준.
 * <p>
 * 목록 제약(1,000만 건 대비, 값은 OrderSearchProperties): 조건 규칙은 OrderSearchCriteriaFactory,
 * 페이지 크기·offset 상한, 건수는 상한까지만 센다.
 */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderQueryRepository orderQueryRepository;
    private final OrderSearchCriteriaFactory criteriaFactory;
    private final SalesChannelCodes salesChannelCodes;
    private final OrderSearchProperties properties;

    public OrderQueryService(OrderQueryRepository orderQueryRepository, OrderSearchCriteriaFactory criteriaFactory,
                             SalesChannelCodes salesChannelCodes, OrderSearchProperties properties) {
        this.orderQueryRepository = orderQueryRepository;
        this.criteriaFactory = criteriaFactory;
        this.salesChannelCodes = salesChannelCodes;
        this.properties = properties;
    }

    /**
     * 매핑안됨 주문은 상태(PAID·PARTIAL_CANCELED 등)와 별개로 mappingPending=true, unmappedItemCount > 0 으로 나온다.
     */
    public CappedPageResponse<OrderSummaryResponse> search(OrderSearchCondition condition, Pageable pageable,
                                                           AuthenticatedUser user) {
        validatePage(pageable);
        OrderSearchCriteria criteria = criteriaFactory.create(condition, user, criteriaFactory.listPeriod());
        int size = pageable.getPageSize();
        if (criteria.sku() != null && criteria.sku().matchesNothing()) {
            return new CappedPageResponse<>(List.of(), pageable.getPageNumber(), size, 0, false);
        }

        List<OrderSummaryResponse> content = orderQueryRepository
                .findPage(criteria, user, pageable.getOffset(), size).stream()
                .map(this::toResponse)
                .toList();

        // 마지막 페이지면 건수를 따로 세지 않는다
        long total;
        if (content.size() < size && (pageable.getOffset() == 0 || !content.isEmpty())) {
            total = pageable.getOffset() + content.size();
        } else {
            total = orderQueryRepository.countUpTo(criteria, user, properties.countCap() + 1);
        }
        boolean capped = total > properties.countCap();
        return new CappedPageResponse<>(content, pageable.getPageNumber(), size,
                capped ? properties.countCap() : total, capped);
    }

    /**
     * TODO: 항목(판매상품명/제품명 조인, 매핑안됨 항목은 채널 코드) + 회차·회차항목
     */
    @ScopeCheck(ScopeTarget.ORDER)
    public OrderDetailResponse getDetail(@ScopeId Long orderId, AuthenticatedUser user) {
        throw new UnsupportedOperationException("주문 상세 미구현");
    }

    private void validatePage(Pageable pageable) {
        if (pageable.getPageSize() > properties.maxPageSize()) {
            throw new InvalidRequestException("페이지 크기는 최대 %,d입니다".formatted(properties.maxPageSize()));
        }
        if (pageable.getOffset() > properties.maxOffset()) {
            throw new InvalidRequestException(
                    "%,d번째 이후 행은 조회할 수 없습니다. 기간이나 조건을 좁혀 다시 조회하세요".formatted(properties.maxOffset()));
        }
    }

    private OrderSummaryResponse toResponse(Row row) {
        return new OrderSummaryResponse(row.orderId(), row.orderNo(), salesChannelCodes.codeOf(row.salesChannelId()),
                row.channelOrderNo(), row.status(), row.mappingPending(), row.ordererName(), row.receiverName(),
                row.paidAmount(), row.currency(), row.itemCount(), row.unmappedItemCount(), row.orderedAt());
    }
}
