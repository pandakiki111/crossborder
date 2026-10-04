package com.crossborder.oms.repository;

import com.crossborder.common.entity.order.OrderItemStatus;
import com.crossborder.common.entity.order.OrderItemType;
import com.crossborder.common.entity.order.QOrder;
import com.crossborder.common.entity.order.QOrderItem;
import com.crossborder.common.entity.product.QSalesChannel;
import com.crossborder.oms.dto.order.OrderSearchCondition;
import com.crossborder.oms.dto.order.OrderSummaryResponse;
import com.crossborder.oms.security.AuthenticatedUser;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 주문 목록 동적 조회 (QueryDSL)
 */
@Repository
public class OrderQueryRepository {

    private static final QOrder order = QOrder.order;
    private static final QOrderItem item = QOrderItem.orderItem;
    private static final QSalesChannel channel = QSalesChannel.salesChannel;

    private final JPAQueryFactory queryFactory;

    public OrderQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /**
     * 정렬은 주문일시 최신순 고정.
     */
    public Page<OrderSummaryResponse> search(OrderSearchCondition condition, Pageable pageable,
                                             AuthenticatedUser user) {
        BooleanExpression[] where = conditions(condition, user);

        List<OrderSummaryResponse> content = queryFactory
                .select(Projections.constructor(OrderSummaryResponse.class,
                        order.id,
                        order.orderNo,
                        channel.code,
                        order.channelOrderNo,
                        order.status,
                        order.mappingPending,
                        order.ordererName,
                        order.receiverName,
                        order.paidAmount,
                        order.currency,
                        JPAExpressions.select(item.count()).from(item)
                                .where(item.orderId.eq(order.id), item.status.eq(OrderItemStatus.ORDERED)),
                        JPAExpressions.select(item.count()).from(item)
                                .where(item.orderId.eq(order.id), item.status.eq(OrderItemStatus.ORDERED),
                                        item.itemType.eq(OrderItemType.SALE_PRODUCT), item.saleProductId.isNull()),
                        order.orderedAt))
                .from(order)
                .join(channel).on(channel.id.eq(order.salesChannelId))
                .where(where)
                .orderBy(order.orderedAt.desc(), order.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory.select(order.count()).from(order).where(where).fetchOne();
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    private static BooleanExpression[] conditions(OrderSearchCondition c, AuthenticatedUser user) {
        return new BooleanExpression[]{
                scope(user),
                c.status() == null ? null : order.status.eq(c.status()),
                c.mappingPending() == null ? null : order.mappingPending.eq(c.mappingPending()),
                c.salesChannelId() == null ? null : order.salesChannelId.eq(c.salesChannelId()),
                StringUtils.hasText(c.orderNo()) ? order.orderNo.eq(c.orderNo().trim()) : null,
                StringUtils.hasText(c.channelOrderNo()) ? order.channelOrderNo.eq(c.channelOrderNo().trim()) : null,
                StringUtils.hasText(c.receiverName()) ? order.receiverName.contains(c.receiverName().trim()) : null,
                c.orderedFrom() == null ? null : order.orderedAt.goe(c.orderedFrom().atStartOfDay()),
                c.orderedTo() == null ? null : order.orderedAt.lt(c.orderedTo().plusDays(1).atStartOfDay())
        };
    }

    /**
     * ADMIN 전체 / COMPANY_STAFF 자기 회사 / BRAND_STAFF 자기 브랜드 항목이 있는 주문 / WORKER 없음.
     * ScopePolicy의 주문 규칙과 같다 (OrderScopeConsistencyTest가 일치를 검증).
     * 매핑안됨 항목도 브랜드가 있으므로 매핑안됨 주문도 같은 규칙으로 보인다.
     */
    private static BooleanExpression scope(AuthenticatedUser user) {
        return switch (user.role()) {
            case ADMIN -> null;
            case WORKER -> Expressions.FALSE.isTrue();
            case COMPANY_STAFF -> order.companyId.eq(user.companyId());
            case BRAND_STAFF -> JPAExpressions.selectOne().from(item)
                    .where(item.orderId.eq(order.id), item.brandId.eq(user.brandId()))
                    .exists();
        };
    }
}
