package com.crossborder.common.entity.product;

import com.crossborder.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매상품 구성 (제품 N:M + 수량, 구성 고정 사은품 포함)
 */
@Getter
@Entity
@Table(name = "sale_product_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SaleProductItem extends BaseAuditEntity {

    @Column(name = "sale_product_id", nullable = false, updatable = false)
    private Long saleProductId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    /** 사은품 여부 (재고 차감 대상, 매출 제외) */
    @Column(name = "is_gift", nullable = false, updatable = false)
    private boolean gift;

    private SaleProductItem(Long saleProductId, Long productId, int quantity, boolean gift) {
        requirePositive(quantity);
        this.saleProductId = saleProductId;
        this.productId = productId;
        this.quantity = quantity;
        this.gift = gift;
    }

    public static SaleProductItem create(Long saleProductId, Long productId, int quantity) {
        return new SaleProductItem(saleProductId, productId, quantity, false);
    }

    public static SaleProductItem createGift(Long saleProductId, Long productId, int quantity) {
        return new SaleProductItem(saleProductId, productId, quantity, true);
    }

    public void changeQuantity(int quantity) {
        requirePositive(quantity);
        this.quantity = quantity;
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("구성 수량은 1 이상이어야 합니다. quantity=" + quantity);
        }
    }
}
