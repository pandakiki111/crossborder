package com.crossborder.common.entity.order;

import com.crossborder.common.entity.BaseAuditEntity;
import com.crossborder.common.entity.product.Product;
import com.crossborder.common.entity.product.SaleProduct;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 항목 (수량 부분취소는 행 분할로 처리)
 * <p>
 * item_type별 참조 규칙(chk_order_items_type_ref)은 타입별 생성 메서드로 보장한다.
 * <p>
 * 사은품 출처 규칙 — GIFT_PRODUCT면 giftSource 필수(EVENT면 giftEventId 필수), 구매 항목은 둘 다 null —
 * 은 이 클래스의 생성 메서드가 유일한 강제 지점이다. DB CHECK 없음 — 온라인 마이그레이션 비용으로 의도적 제외, V9 주석 참조.
 * 생성자를 거치지 않는 JDBC 대량 쓰기(OrderBatchWriter, GiftEventApplier)는 이 클래스로 만든 값만 옮기거나 같은 규칙으로 쓴다.
 */
@Getter
@Entity
@Table(name = "order_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem extends BaseAuditEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    /** 참조 대상에서 주문 시점 복사. 매핑안됨 항목은 수집 시점의 브랜드 (매핑은 이 브랜드 안에서만) */
    @Column(name = "brand_id", nullable = false, updatable = false)
    private Long brandId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 20, updatable = false)
    private OrderItemType itemType;

    /** 매핑 전 항목은 null (매핑 시 확정) */
    @Column(name = "sale_product_id")
    private Long saleProductId;

    @Column(name = "product_id", updatable = false)
    private Long productId;

    /** 사은품 출처 (GIFT_PRODUCT만, 필수). 구매 항목은 null */
    @Enumerated(EnumType.STRING)
    @Column(name = "gift_source", length = 20, updatable = false)
    private GiftSource giftSource;

    /** EVENT 사은품의 이벤트 */
    @Column(name = "gift_event_id", updatable = false)
    private Long giftEventId;

    /** 채널 상품코드 (마켓 수신값). 채널 수신 항목만 */
    @Column(name = "channel_product_code", length = 100, updatable = false)
    private String channelProductCode;

    /** 채널 옵션코드 (마켓 수신값, 옵션 없음 = '') */
    @Column(name = "channel_option_code", length = 30, updatable = false)
    private String channelOptionCode;

    @Column(nullable = false)
    private int quantity;

    /** 주문 시점 단가 스냅샷 (사은품은 0) */
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderItemStatus status;

    private OrderItem(Long orderId, Long brandId, OrderItemType itemType, Long saleProductId, Long productId,
                      GiftSource giftSource, Long giftEventId, String channelProductCode, String channelOptionCode,
                      int quantity, BigDecimal unitPrice, OrderItemStatus status) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("주문 수량은 1 이상이어야 합니다. quantity=" + quantity);
        }
        this.orderId = orderId;
        this.brandId = brandId;
        this.itemType = itemType;
        this.saleProductId = saleProductId;
        this.productId = productId;
        this.giftSource = giftSource;
        this.giftEventId = giftEventId;
        this.channelProductCode = channelProductCode;
        this.channelOptionCode = channelOptionCode;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.status = status;
    }

    /**
     * brandId는 판매상품에서 주문 시점 복사
     */
    public static OrderItem ofSaleProduct(Long orderId, SaleProduct saleProduct, int quantity, BigDecimal unitPrice) {
        Objects.requireNonNull(saleProduct.getId(), "저장되지 않은 판매상품으로 주문 항목을 만들 수 없습니다.");
        return new OrderItem(orderId, saleProduct.getBrandId(), OrderItemType.SALE_PRODUCT, saleProduct.getId(), null,
                null, null, null, null, quantity, unitPrice, OrderItemStatus.ORDERED);
    }

    /**
     * 채널 수신 항목. 채널 코드는 수신값 그대로 보존한다.
     *
     * @param brandId     수집 브랜드 (매핑안됨 항목도 브랜드는 확정)
     * @param saleProduct 매핑으로 확정된 판매상품 (brandId 소속이어야 함). 매핑이 없으면 null → 매핑안됨 항목
     */
    public static OrderItem ofChannelProduct(Long orderId, Long brandId, String channelProductCode,
                                             String channelOptionCode, SaleProduct saleProduct,
                                             int quantity, BigDecimal unitPrice) {
        Objects.requireNonNull(brandId, "브랜드가 없습니다.");
        Objects.requireNonNull(channelProductCode, "채널 상품코드가 없습니다.");
        if (saleProduct != null) {
            requireSameBrand(brandId, saleProduct);
        }
        return new OrderItem(orderId, brandId, OrderItemType.SALE_PRODUCT,
                saleProduct == null ? null : saleProduct.getId(), null, null, null,
                channelProductCode, channelOptionCode, quantity, unitPrice, OrderItemStatus.ORDERED);
    }

    /**
     * 운영자 수동 증정 (MANUAL). brandId는 제품에서 주문 시점 복사, 단가 0
     */
    public static OrderItem ofManualGift(Long orderId, Product product, int quantity) {
        Objects.requireNonNull(product.getId(), "저장되지 않은 제품으로 주문 항목을 만들 수 없습니다.");
        return new OrderItem(orderId, product.getBrandId(), OrderItemType.GIFT_PRODUCT, null, product.getId(),
                GiftSource.MANUAL, null, null, null, quantity, BigDecimal.ZERO, OrderItemStatus.ORDERED);
    }

    /**
     * 이벤트 자동 증정 (EVENT). 이벤트 품목 1종당 1행 — 같은 제품이라도 다른 항목과 합산하지 않는다. 단가 0
     */
    public static OrderItem ofEventGift(Long orderId, Long brandId, Long productId, int quantity, Long giftEventId) {
        Objects.requireNonNull(giftEventId, "이벤트가 없습니다.");
        return new OrderItem(orderId, brandId, OrderItemType.GIFT_PRODUCT, null, Objects.requireNonNull(productId),
                GiftSource.EVENT, giftEventId, null, null, quantity, BigDecimal.ZERO, OrderItemStatus.ORDERED);
    }

    /**
     * 채널 수신 사은품 항목. 상품 매핑을 거치지 않고 채널 상품코드 = 제품 SKU로 확정된 제품을 받는다.
     * 단가는 수신값 그대로 저장한다 (통관 신고 시 사은품 가격 처리용).
     *
     * @param brandId 수집 브랜드 — 제품도 이 브랜드 소속이어야 한다
     */
    public static OrderItem ofChannelGift(Long orderId, Long brandId, String channelProductCode,
                                          String channelOptionCode, Product product, int quantity,
                                          BigDecimal unitPrice) {
        Objects.requireNonNull(product.getId(), "저장되지 않은 제품으로 주문 항목을 만들 수 없습니다.");
        if (!brandId.equals(product.getBrandId())) {
            throw new IllegalArgumentException("항목 브랜드와 사은품 제품 브랜드가 다릅니다. brandId=" + brandId
                    + ", sku=" + product.getSku() + ", productBrandId=" + product.getBrandId());
        }
        return new OrderItem(orderId, brandId, OrderItemType.GIFT_PRODUCT, null, product.getId(),
                GiftSource.COLLECTED, null, channelProductCode, channelOptionCode, quantity, unitPrice,
                OrderItemStatus.ORDERED);
    }

    /**
     * 매핑안됨 항목을 판매상품으로 확정. 항목 브랜드의 판매상품이어야 한다.
     */
    public void mapTo(SaleProduct saleProduct) {
        if (isMapped()) {
            throw new IllegalStateException("이미 판매상품이 확정된 항목입니다. id=" + getId());
        }
        requireSameBrand(brandId, saleProduct);
        this.saleProductId = saleProduct.getId();
    }

    private static void requireSameBrand(Long brandId, SaleProduct saleProduct) {
        Objects.requireNonNull(saleProduct.getId(), "저장되지 않은 판매상품으로 주문 항목을 만들 수 없습니다.");
        if (!brandId.equals(saleProduct.getBrandId())) {
            throw new IllegalArgumentException("항목 브랜드와 판매상품 브랜드가 다릅니다. brandId=" + brandId
                    + ", saleProductId=" + saleProduct.getId() + ", saleProductBrandId=" + saleProduct.getBrandId());
        }
    }

    /** 사은품은 항상 확정, 구매 항목은 판매상품이 있어야 확정 */
    public boolean isMapped() {
        return isGift() || saleProductId != null;
    }

    /**
     * 항목 전체 취소.
     * <p>
     * 이 항목이 INSTRUCTED 이후 회차에 물려 있지 않은지는 서비스 책임 (ShipmentItem 조회 필요).
     */
    public void cancel() {
        requireOrdered();
        this.status = OrderItemStatus.CANCELED;
    }

    /**
     * 수량 부분취소. 이 항목의 수량을 줄이고, 취소분을 CANCELED 상태의 새 행으로 반환한다.
     * 사은품은 본품 취소에 딸려가므로 전체 취소만 가능하다.
     * <p>
     * 이 항목이 INSTRUCTED 이후 회차에 물려 있지 않은지는 서비스 책임 (ShipmentItem 조회 필요).
     */
    public OrderItem splitCanceled(int cancelQuantity) {
        requireOrdered();
        if (isGift()) {
            throw new IllegalStateException("사은품은 수량 부분취소 없이 전체 취소만 가능합니다. id=" + getId());
        }
        if (cancelQuantity <= 0 || cancelQuantity >= quantity) {
            throw new IllegalArgumentException(
                    "부분취소 수량은 1 이상, 현재 수량(" + quantity + ") 미만이어야 합니다. cancelQuantity=" + cancelQuantity);
        }
        this.quantity -= cancelQuantity;
        return new OrderItem(orderId, brandId, itemType, saleProductId, productId, giftSource, giftEventId,
                channelProductCode, channelOptionCode, cancelQuantity, unitPrice, OrderItemStatus.CANCELED);
    }

    public boolean isOrdered() {
        return status == OrderItemStatus.ORDERED;
    }

    public boolean isGift() {
        return itemType == OrderItemType.GIFT_PRODUCT;
    }

    private void requireOrdered() {
        if (!isOrdered()) {
            throw new IllegalStateException("이미 취소된 주문 항목입니다. id=" + getId());
        }
    }
}
