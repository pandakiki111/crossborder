package com.crossborder.common.entity.gift;

import com.crossborder.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사은품 이벤트 증정 품목 (제품 = SKU 단위). 증정 수량은 이벤트가 정한다 (품목별 수량 없음).
 * <p>
 * granted_qty는 증정 실행이 이벤트 락 아래에서 원자적 UPDATE로 올린다 (엔티티로 바꾸지 않는다).
 * 항목 취소로 줄지 않는다 — 지급 실적이다.
 */
@Getter
@Entity
@Table(name = "gift_event_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GiftEventItem extends BaseAuditEntity {

    @Column(name = "gift_event_id", nullable = false, updatable = false)
    private Long giftEventId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    /** SEQUENTIAL 순서 (작을수록 먼저) */
    @Column(nullable = false, updatable = false)
    private int priority;

    /** SEQUENTIAL·RANDOM 증정 한도. null = 무제한 (ALWAYS는 항상 null) */
    @Column(name = "limit_qty", updatable = false)
    private Integer limitQty;

    @Column(name = "granted_qty", nullable = false, insertable = false, updatable = false)
    private int grantedQty;

    private GiftEventItem(Long giftEventId, Long productId, int priority, Integer limitQty) {
        this.giftEventId = Objects.requireNonNull(giftEventId);
        this.productId = Objects.requireNonNull(productId, "증정 제품이 없습니다.");
        this.priority = priority;
        this.limitQty = limitQty;
    }

    /**
     * @throws IllegalArgumentException 한도가 1 미만이거나, ALWAYS인데 한도를 지정함
     */
    public static GiftEventItem create(Long giftEventId, GiftGrantType grantType, Long productId, int priority,
                                       Integer limitQty) {
        if (limitQty != null && limitQty < 1) {
            throw new IllegalArgumentException("증정 한도는 1 이상이어야 합니다. limitQty=" + limitQty);
        }
        if (grantType == GiftGrantType.ALWAYS && limitQty != null) {
            throw new IllegalArgumentException("ALWAYS 이벤트 품목은 한도를 두지 않습니다.");
        }
        return new GiftEventItem(giftEventId, productId, priority, limitQty);
    }
}
