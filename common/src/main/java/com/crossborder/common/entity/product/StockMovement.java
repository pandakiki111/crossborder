package com.crossborder.common.entity.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 물리 재고 원장 (append-only)
 */
@Getter
@Entity
@Table(name = "stock_movements")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 20, updatable = false)
    private MovementType movementType;

    /** 변동 수량 (부호 포함: 입고 +, 출고 -) */
    @Column(nullable = false, updatable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", length = 20, updatable = false)
    private StockReferenceType referenceType;

    /** 근거 문서 id (이형 참조라 FK 없음) */
    @Column(name = "reference_id", updatable = false)
    private Long referenceId;

    @Column(length = 100, updatable = false)
    private String note;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @CreatedBy
    @Column(name = "created_user_id", nullable = false, updatable = false)
    private Long createdUserId;

    private StockMovement(Long productId, MovementType movementType, int quantity,
                          StockReferenceType referenceType, Long referenceId, String note) {
        if (!movementType.isValidQuantity(quantity)) {
            throw new IllegalArgumentException(
                    "변동 사유와 수량 부호가 맞지 않습니다. type=" + movementType + ", quantity=" + quantity);
        }
        this.productId = productId;
        this.movementType = movementType;
        this.quantity = quantity;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.note = note;
    }

    public static StockMovement of(Long productId, MovementType movementType, int quantity,
                                   StockReferenceType referenceType, Long referenceId, String note) {
        return new StockMovement(productId, movementType, quantity, referenceType, referenceId, note);
    }

    public static StockMovement manualAdjust(Long productId, int quantity, String note) {
        return new StockMovement(productId, MovementType.ADJUST, quantity, StockReferenceType.MANUAL, null, note);
    }
}
