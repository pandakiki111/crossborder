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
 */
@Getter
@Entity
@Table(name = "order_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem extends BaseAuditEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    /** 참조 대상에서 주문 시점 복사 */
    @Column(name = "brand_id", nullable = false, updatable = false)
    private Long brandId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 20, updatable = false)
    private OrderItemType itemType;

    @Column(name = "sale_product_id", updatable = false)
    private Long saleProductId;

    @Column(name = "product_id", updatable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    /** 주문 시점 단가 스냅샷 (사은품은 0) */
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderItemStatus status;

    private OrderItem(Long orderId, Long brandId, OrderItemType itemType, Long saleProductId, Long productId,
                      int quantity, BigDecimal unitPrice, OrderItemStatus status) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("주문 수량은 1 이상이어야 합니다. quantity=" + quantity);
        }
        this.orderId = orderId;
        this.brandId = brandId;
        this.itemType = itemType;
        this.saleProductId = saleProductId;
        this.productId = productId;
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
                quantity, unitPrice, OrderItemStatus.ORDERED);
    }

    /**
     * brandId는 제품에서 주문 시점 복사
     */
    public static OrderItem ofGift(Long orderId, Product product, int quantity) {
        Objects.requireNonNull(product.getId(), "저장되지 않은 제품으로 주문 항목을 만들 수 없습니다.");
        return new OrderItem(orderId, product.getBrandId(), OrderItemType.GIFT_PRODUCT, null, product.getId(),
                quantity, BigDecimal.ZERO, OrderItemStatus.ORDERED);
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
        return new OrderItem(orderId, brandId, itemType, saleProductId, productId,
                cancelQuantity, unitPrice, OrderItemStatus.CANCELED);
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
