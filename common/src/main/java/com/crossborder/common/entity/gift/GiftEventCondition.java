package com.crossborder.common.entity.gift;

import com.crossborder.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사은품 이벤트 상품 조건 1행.
 * <ul>
 *   <li>SALE_PRODUCT: 판매상품코드 일치. optionCode가 있으면 항목의 채널 옵션코드도 일치해야 한다 (null = 옵션 무관)</li>
 *   <li>SKU: 그 제품이 구성에 든 판매상품 구매 (판정에서 구성을 보는 유일한 지점)</li>
 * </ul>
 */
@Getter
@Entity
@Table(name = "gift_event_conditions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GiftEventCondition extends BaseAuditEntity {

    @Column(name = "gift_event_id", nullable = false, updatable = false)
    private Long giftEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private GiftConditionTarget targetType;

    @Column(name = "sale_product_code", length = 100, updatable = false)
    private String saleProductCode;

    @Column(name = "option_code", length = 30, updatable = false)
    private String optionCode;

    @Column(length = 50, updatable = false)
    private String sku;

    private GiftEventCondition(Long giftEventId, GiftConditionTarget targetType, String saleProductCode,
                               String optionCode, String sku) {
        this.giftEventId = Objects.requireNonNull(giftEventId);
        this.targetType = targetType;
        this.saleProductCode = saleProductCode;
        this.optionCode = optionCode;
        this.sku = sku;
    }

    public static GiftEventCondition ofSaleProduct(Long giftEventId, String saleProductCode, String optionCode) {
        if (saleProductCode == null || saleProductCode.isBlank()) {
            throw new IllegalArgumentException("판매상품코드가 없습니다.");
        }
        return new GiftEventCondition(giftEventId, GiftConditionTarget.SALE_PRODUCT, saleProductCode.trim(),
                optionCode, null);
    }

    public static GiftEventCondition ofSku(Long giftEventId, String sku) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("SKU가 없습니다.");
        }
        return new GiftEventCondition(giftEventId, GiftConditionTarget.SKU, null, null, sku.trim());
    }
}
