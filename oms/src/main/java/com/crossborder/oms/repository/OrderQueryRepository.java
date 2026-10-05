package com.crossborder.oms.repository;

import com.crossborder.common.entity.order.OrderItemStatus;
import com.crossborder.common.entity.order.OrderItemType;
import com.crossborder.common.entity.order.OrderStatus;
import com.crossborder.common.entity.order.QOrder;
import com.crossborder.common.entity.order.QOrderItem;
import com.crossborder.common.entity.order.QShipment;
import com.crossborder.common.entity.order.ShipmentStatus;
import com.crossborder.common.entity.product.QProduct;
import com.crossborder.common.entity.product.QSaleProductItem;
import com.crossborder.oms.repository.OrderSearchCriteria.SkuFilter;
import com.crossborder.oms.security.AuthenticatedUser;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/**
 * 주문 목록 동적 조회 (QueryDSL). 정렬은 주문일시 최신순 고정.
 * <p>
 * 1,000만 건 실측(README "대용량 조회 검증")에서 정한 쿼리 형태:
 * <ul>
 *   <li>판매채널을 조인하지 않는다 — 조인하면 옵티마이저가 채널(3행)부터 읽고 주문 전체를 정렬하는 순서를 고른다.
 *       채널 코드는 앱 캐시로 붙인다 (SalesChannelCodes)</li>
 *   <li>지연 조인 — 조건·정렬·offset은 ID만으로 끝내고(인덱스), 표시 컬럼과 항목 수 서브쿼리는 그 페이지 ID에만 붙인다</li>
 *   <li>항목·회차 조건은 상관 count 서브쿼리 형태 ((...) &gt; 0, = 0). EXISTS로 쓰면 MariaDB가 세미조인으로 바꿔
 *       항목·회차 테이블부터 읽는 순서를 고를 수 있다 (365일 배송상태 9~10초)</li>
 * </ul>
 * 같은 조건이라도 목록과 건수의 최적 형태가 다른 곳이 있어 {@link Usage}로 나눈다.
 */
@Repository
public class OrderQueryRepository {

    private static final QOrder order = QOrder.order;
    private static final QOrderItem item = QOrderItem.orderItem;
    private static final QShipment shipment = QShipment.shipment;
    private static final QProduct product = QProduct.product;
    private static final QSaleProductItem composition = QSaleProductItem.saleProductItem;

    private final JPAQueryFactory queryFactory;

    public OrderQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /**
     * 목록 행. 채널은 ID로 내리고 코드는 서비스가 붙인다.
     *
     * @param itemCount         유효(ORDERED) 항목 수
     * @param unmappedItemCount 매핑안됨 항목 수
     */
    public record Row(Long orderId, String orderNo, Long salesChannelId, String channelOrderNo, OrderStatus status,
                      boolean mappingPending, String ordererName, String receiverName, BigDecimal paidAmount,
                      String currency, long itemCount, long unmappedItemCount, LocalDateTime orderedAt) {
    }

    /**
     * 목록과 건수의 서브쿼리 형태 구분.
     * 브랜드 조건만 다르다: 목록은 최신순으로 주문을 읽다 20건이 차면 멈추므로 주문 → 항목 프로브가 빠르고,
     * 건수는 일치가 상한보다 적으면 기간 전체를 훑게 되므로 브랜드 항목에서 출발하는 EXISTS(세미조인)가 빠르다.
     * (브랜드 665, 365일: 목록 프로브 22ms / EXISTS 2.6초, 건수 프로브 8.3초 / EXISTS 1.5초)
     */
    enum Usage {
        PAGE,
        COUNT
    }

    /**
     * 지연 조인: 1) 조건에 맞는 페이지의 주문 ID만 인덱스 순서로 고른다 2) 그 ID의 표시 컬럼·항목 수를 읽는다.
     * offset 10만에서 offset 그대로 103ms → 지연 조인 12ms.
     */
    public List<Row> findPage(OrderSearchCriteria criteria, AuthenticatedUser user, long offset, int limit) {
        List<Long> ids = queryFactory
                .select(order.id)
                .from(order)
                .where(conditions(criteria, user, Usage.PAGE))
                .orderBy(order.orderedAt.desc(), order.id.desc())
                .offset(offset)
                .limit(limit)
                .fetch();
        if (ids.isEmpty()) {
            return List.of();
        }
        return queryFactory
                .select(Projections.constructor(Row.class,
                        order.id,
                        order.orderNo,
                        order.salesChannelId,
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
                .where(order.id.in(ids))
                .orderBy(order.orderedAt.desc(), order.id.desc())
                .fetch();
    }

    /**
     * 상한 건수: 최대 limit건까지만 센다 (정확한 총건수는 세지 않는다). 상한 10,001: 365일 전체 1.4ms, 정확 count 1.3초.
     */
    public int countUpTo(OrderSearchCriteria criteria, AuthenticatedUser user, int limit) {
        return queryFactory
                .select(order.id)
                .from(order)
                .where(conditions(criteria, user, Usage.COUNT))
                .limit(limit)
                .fetch()
                .size();
    }

    /**
     * SKU → 제품 → 그 제품을 구성에 포함한 판매상품. 그다음 해당 항목 수를 임계+1까지만 세서 경로를 정한다
     * ((sale_product_id, order_id) 인덱스로 약 1ms).
     * <ul>
     *   <li>임계 이하(희소): 항목에서 출발 — 최대 임계 건의 항목만 읽고 주문을 붙인다</li>
     *   <li>임계 초과(밀집): 주문에서 출발 — 기간을 최신순으로 읽으며 주문마다 항목을 확인하고 페이지가 차면 멈춘다</li>
     * </ul>
     * 실측: 1제품(약 1,000항목) IN 2~6ms / 프로브 211~264ms, 1,000제품 IN 33~64초 / 프로브 3ms.
     */
    public SkuFilter resolveSku(String sku, boolean partial, int sparseItemThreshold) {
        List<Long> productIds = queryFactory
                .select(product.id)
                .from(product)
                .where(partial ? product.sku.contains(sku) : product.sku.eq(sku))
                .fetch();
        if (productIds.isEmpty()) {
            return new SkuFilter(List.of(), List.of(), false);
        }
        List<Long> saleProductIds = queryFactory
                .select(composition.saleProductId).distinct()
                .from(composition)
                .where(composition.productId.in(productIds))
                .fetch();
        int items = queryFactory
                .select(item.id)
                .from(item)
                .where(skuItem(saleProductIds, productIds))
                .limit(sparseItemThreshold + 1L)
                .fetch()
                .size();
        return new SkuFilter(saleProductIds, productIds, items > sparseItemThreshold);
    }

    private static BooleanExpression[] conditions(OrderSearchCriteria c, AuthenticatedUser user, Usage usage) {
        List<BooleanExpression> conditions = new ArrayList<>();
        conditions.add(scope(user, usage));
        conditions.add(order.orderedAt.goe(c.orderedFrom()));
        conditions.add(order.orderedAt.lt(c.orderedTo()));
        conditions.add(c.statuses().isEmpty() ? null : order.status.in(c.statuses()));
        conditions.add(c.mappingPending() == null ? null : order.mappingPending.eq(c.mappingPending()));
        conditions.add(c.salesChannelId() == null ? null : order.salesChannelId.eq(c.salesChannelId()));
        conditions.add(c.brandId() == null ? null : hasBrand(c.brandId(), usage));
        conditions.add(shipmentCondition(c.shipmentStatuses(), c.unsplit()));
        conditions.add(c.orderNo() == null ? null : order.orderNo.eq(c.orderNo()));
        conditions.add(c.channelOrderNos().isEmpty() ? null : order.channelOrderNo.in(c.channelOrderNos()));
        conditions.add(c.channelOrderNoContains() == null ? null : order.channelOrderNo.contains(c.channelOrderNoContains()));
        conditions.add(c.sku() == null ? null : skuCondition(c.sku()));
        return conditions.stream().filter(Objects::nonNull).toArray(BooleanExpression[]::new);
    }

    /**
     * ADMIN 전체 / COMPANY_STAFF 자기 회사 / BRAND_STAFF 자기 브랜드 항목이 있는 주문 / WORKER 없음.
     * ScopePolicy의 주문 규칙과 같다 (OrderScopeConsistencyTest가 일치를 검증).
     * 매핑안됨 항목도 브랜드가 있으므로 매핑안됨 주문도 같은 규칙으로 보인다.
     */
    private static BooleanExpression scope(AuthenticatedUser user, Usage usage) {
        return switch (user.role()) {
            case ADMIN -> null;
            case WORKER -> Expressions.FALSE.isTrue();
            case COMPANY_STAFF -> order.companyId.eq(user.companyId());
            case BRAND_STAFF -> hasBrand(user.brandId(), usage);
        };
    }

    /** 주문에 그 브랜드 항목이 있음. 형태는 {@link Usage} 참고. 목록 프로브는 (order_id, brand_id) 인덱스만 읽는다 */
    private static BooleanExpression hasBrand(Long brandId, Usage usage) {
        return switch (usage) {
            case PAGE -> JPAExpressions.select(item.count()).from(item)
                    .where(item.orderId.eq(order.id), item.brandId.eq(brandId))
                    .gt(0L);
            case COUNT -> JPAExpressions.selectOne().from(item)
                    .where(item.orderId.eq(order.id), item.brandId.eq(brandId))
                    .exists();
        };
    }

    /**
     * 회차 상태 IN이 하나라도 있음, 또는 미분리(유효 회차 = CANCELED 아닌 회차 없음). 둘 다 주면 OR.
     * 미분리를 "회차 없음"이 아니라 "유효 회차 없음"으로 보는 이유: 재분리는 기존 회차를 CANCELED로 남기고 새로 나누므로,
     * CANCELED 회차만 남은 주문은 다시 나눠야 하는 주문이다.
     * EXISTS / NOT EXISTS와 결과가 같다 (OrderSearchTest가 경계 케이스로 고정).
     */
    private static BooleanExpression shipmentCondition(List<ShipmentStatus> statuses, boolean unsplit) {
        BooleanExpression hasStatus = statuses.isEmpty() ? null
                : JPAExpressions.select(shipment.count()).from(shipment)
                .where(shipment.orderId.eq(order.id), shipment.status.in(statuses))
                .gt(0L);
        BooleanExpression noValidShipment = !unsplit ? null
                : JPAExpressions.select(shipment.count()).from(shipment)
                .where(shipment.orderId.eq(order.id), shipment.status.ne(ShipmentStatus.CANCELED))
                .eq(0L);
        if (hasStatus != null && noValidShipment != null) {
            return hasStatus.or(noValidShipment);
        }
        return hasStatus != null ? hasStatus : noValidShipment;
    }

    private static BooleanExpression skuCondition(SkuFilter sku) {
        if (sku.dense()) {
            return JPAExpressions.select(item.count()).from(item)
                    .where(item.orderId.eq(order.id), skuItem(sku.saleProductIds(), sku.productIds()))
                    .gt(0L);
        }
        return order.id.in(JPAExpressions.select(item.orderId).from(item)
                .where(skuItem(sku.saleProductIds(), sku.productIds())));
    }

    /** 판매상품 항목(구성에 그 제품) 또는 건별 사은품 항목(그 제품). 취소된 항목도 주문에 들어간 이력이라 포함한다 */
    private static BooleanExpression skuItem(List<Long> saleProductIds, List<Long> productIds) {
        BooleanExpression gift = item.productId.in(productIds);
        return saleProductIds.isEmpty() ? gift : item.saleProductId.in(saleProductIds).or(gift);
    }
}
