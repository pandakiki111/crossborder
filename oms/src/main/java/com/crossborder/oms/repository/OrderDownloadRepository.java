package com.crossborder.oms.repository;

import com.crossborder.common.entity.order.OrderItemStatus;
import com.crossborder.common.entity.order.OrderItemType;
import com.crossborder.common.entity.order.OrderStatus;
import com.crossborder.common.entity.order.QOrder;
import com.crossborder.common.entity.order.QOrderItem;
import com.crossborder.common.entity.organization.QBrand;
import com.crossborder.common.entity.product.QProduct;
import com.crossborder.common.entity.product.QSaleProduct;
import com.crossborder.common.entity.product.QSaleProductItem;
import com.crossborder.oms.repository.OrderQueryRepository.Usage;
import com.crossborder.oms.security.AuthenticatedUser;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 주문 다운로드 조회. 조건은 주문 목록과 같은 빌더(OrderQueryRepository.conditions)를 쓴다 — 같은 조건이면 같은 주문 집합.
 * <p>
 * 페이징 대신 커서(ordered_at, id) 순차 조회: 주문 묶음을 최신순으로 읽고, 그 묶음의 항목을 제품 단위로 전개한다.
 * 조회 시점 스냅샷 일관성은 보장하지 않는다 (묶음 사이에 들어온 주문은 범위에 따라 빠지거나 포함될 수 있다).
 */
@Repository
public class OrderDownloadRepository {

    private static final QOrder order = QOrder.order;
    private static final QOrderItem item = QOrderItem.orderItem;
    private static final QBrand brand = QBrand.brand;
    private static final QSaleProduct saleProduct = QSaleProduct.saleProduct;
    private static final QSaleProductItem composition = QSaleProductItem.saleProductItem;
    private static final QProduct component = new QProduct("component");
    private static final QProduct giftProduct = new QProduct("giftProduct");

    private final JPAQueryFactory queryFactory;

    public OrderDownloadRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 다운로드 주문 (주문 레벨 열) */
    public record DownloadOrder(Long orderId, String orderNo, Long salesChannelId, String channelOrderNo,
                                OrderStatus status, LocalDateTime orderedAt, String receiverName, String receiverPhone,
                                String receiverZipcode, String receiverAddress, String deliveryMemo,
                                BigDecimal totalItemAmount, BigDecimal paidAmount, String currency) {
    }

    /** 커서 위치 (직전 묶음의 마지막 주문). null이면 처음부터 */
    public record Cursor(LocalDateTime orderedAt, Long orderId) {
    }

    /**
     * 사은품 출처. COMPOSITION = 판매상품 구성에 고정된 사은품, ORDER = 주문 건별 사은품 항목
     */
    public enum GiftSource {
        COMPOSITION,
        ORDER
    }

    /**
     * 전개 1행 = 항목의 구성 제품 1개 (건별 사은품 항목은 그 제품 1행, 매핑안됨 항목은 전개 없이 1행).
     *
     * @param productQuantity 항목 수량 × 구성 수량. 매핑안됨이면 null
     * @param sku             매핑안됨이면 null
     */
    public record DownloadLine(Long orderId, String brandName, String saleProductCode, String productName,
                               String channelProductCode, String channelOptionCode, int itemQuantity,
                               BigDecimal unitPrice, String sku, Integer productQuantity, boolean gift,
                               GiftSource giftSource) {
    }

    /** 커서 다음 주문 묶음 (최신순). 목록의 PAGE 형태 조건을 쓴다 — 주문을 인덱스 순서로 읽다 묶음이 차면 멈춘다 */
    public List<DownloadOrder> findOrders(OrderSearchCriteria criteria, AuthenticatedUser user, Cursor after,
                                          int limit) {
        List<BooleanExpression> where = new ArrayList<>(List.of(OrderQueryRepository.conditions(criteria, user, Usage.PAGE)));
        if (after != null) {
            where.add(order.orderedAt.lt(after.orderedAt())
                    .or(order.orderedAt.eq(after.orderedAt()).and(order.id.lt(after.orderId()))));
        }
        return queryFactory
                .select(Projections.constructor(DownloadOrder.class,
                        order.id, order.orderNo, order.salesChannelId, order.channelOrderNo, order.status,
                        order.orderedAt, order.receiverName, order.receiverPhone, order.receiverZipcode,
                        order.receiverAddress, order.deliveryMemo, order.totalItemAmount, order.paidAmount,
                        order.currency))
                .from(order)
                .where(where.toArray(BooleanExpression[]::new))
                .orderBy(order.orderedAt.desc(), order.id.desc())
                .limit(limit)
                .fetch();
    }

    /**
     * 주문들의 유효(ORDERED) 항목을 제품 단위로 전개한다. 취소된 항목은 출고 대상이 아니라 넣지 않는다.
     * 순서: 주문 → 항목 id → 구성 id.
     */
    public List<DownloadLine> findLines(Collection<Long> orderIds) {
        List<Tuple> tuples = queryFactory
                .select(item.orderId, item.itemType, brand.name, saleProduct.code, saleProduct.name,
                        item.channelProductCode, item.channelOptionCode, item.quantity, item.unitPrice,
                        component.sku, composition.quantity, composition.gift, giftProduct.sku, giftProduct.name)
                .from(item)
                .join(brand).on(brand.id.eq(item.brandId))
                .leftJoin(saleProduct).on(saleProduct.id.eq(item.saleProductId))
                .leftJoin(composition).on(composition.saleProductId.eq(item.saleProductId))
                .leftJoin(component).on(component.id.eq(composition.productId))
                .leftJoin(giftProduct).on(giftProduct.id.eq(item.productId))
                .where(item.orderId.in(orderIds), item.status.eq(OrderItemStatus.ORDERED))
                .orderBy(item.orderId.asc(), item.id.asc(), composition.id.asc())
                .fetch();
        return tuples.stream().map(OrderDownloadRepository::toLine).toList();
    }

    private static DownloadLine toLine(Tuple t) {
        Long orderId = t.get(item.orderId);
        String brandName = t.get(brand.name);
        String channelProductCode = t.get(item.channelProductCode);
        String option = t.get(item.channelOptionCode);
        int itemQuantity = t.get(item.quantity);
        BigDecimal unitPrice = t.get(item.unitPrice);
        if (t.get(item.itemType) == OrderItemType.GIFT_PRODUCT) {
            return new DownloadLine(orderId, brandName, null, t.get(giftProduct.name), channelProductCode, option,
                    itemQuantity, unitPrice, t.get(giftProduct.sku), itemQuantity, true, GiftSource.ORDER);
        }
        String sku = t.get(component.sku);
        if (sku == null) {
            // 매핑안됨: 판매상품이 없어 전개할 수 없다 (채널 상품코드만)
            return new DownloadLine(orderId, brandName, null, null, channelProductCode, option, itemQuantity,
                    unitPrice, null, null, false, null);
        }
        boolean gift = Boolean.TRUE.equals(t.get(composition.gift));
        return new DownloadLine(orderId, brandName, t.get(saleProduct.code), t.get(saleProduct.name),
                channelProductCode, option, itemQuantity, unitPrice, sku, itemQuantity * t.get(composition.quantity),
                gift, gift ? GiftSource.COMPOSITION : null);
    }
}
