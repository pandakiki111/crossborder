package com.crossborder.common.entity.order;

import com.crossborder.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 출고 회차 (분리·지시·물류 작업의 단위, 한 회차 = 단일 브랜드)
 */
@Getter
@Entity
@Table(name = "shipments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipment extends BaseAuditEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "brand_id", nullable = false, updatable = false)
    private Long brandId;

    @Column(name = "shipment_no", nullable = false, length = 50, updatable = false)
    private String shipmentNo;

    @Column(name = "round_no", nullable = false, updatable = false)
    private int roundNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShipmentStatus status;

    /** 단일 회차면 null */
    @Enumerated(EnumType.STRING)
    @Column(name = "split_reason", length = 30)
    private SplitReason splitReason;

    /** 회차 금액 (통관 신고 참조값) */
    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "instructed_by")
    private Long instructedBy;

    @Column(name = "instructed_at")
    private LocalDateTime instructedAt;

    private Shipment(Order order, Long brandId, int roundNo, SplitReason splitReason, BigDecimal totalAmount) {
        Objects.requireNonNull(order.getId(), "저장되지 않은 주문으로 회차를 만들 수 없습니다.");
        if (roundNo <= 0) {
            throw new IllegalArgumentException("회차는 1부터 시작합니다. roundNo=" + roundNo);
        }
        this.orderId = order.getId();
        this.brandId = brandId;
        this.roundNo = roundNo;
        this.shipmentNo = order.getOrderNo() + "-" + roundNo;
        this.splitReason = splitReason;
        this.totalAmount = totalAmount;
        this.status = ShipmentStatus.CREATED;
    }

    /**
     * shipmentNo({order_no}-{round_no}) 생성을 위해 Order를 받는다.
     */
    public static Shipment create(Order order, Long brandId, int roundNo, SplitReason splitReason,
                                  BigDecimal totalAmount) {
        return new Shipment(order, brandId, roundNo, splitReason, totalAmount);
    }

    public void changeTotalAmount(BigDecimal totalAmount) {
        requireStatus(ShipmentStatus.CREATED, "금액변경");
        this.totalAmount = totalAmount;
    }

    public void instruct(Long instructedBy) {
        instruct(instructedBy, LocalDateTime.now());
    }

    public void instruct(Long instructedBy, LocalDateTime instructedAt) {
        requireStatus(ShipmentStatus.CREATED, "출고지시");
        this.status = ShipmentStatus.INSTRUCTED;
        this.instructedBy = instructedBy;
        this.instructedAt = instructedAt;
    }

    public void pick() {
        transit(ShipmentStatus.INSTRUCTED, ShipmentStatus.PICKED);
    }

    public void pack() {
        transit(ShipmentStatus.PICKED, ShipmentStatus.PACKED);
    }

    public void palletize() {
        transit(ShipmentStatus.PACKED, ShipmentStatus.PALLETIZED);
    }

    public void masterShip() {
        transit(ShipmentStatus.PALLETIZED, ShipmentStatus.MASTER_SHIPPED);
    }

    /**
     * 지시 전(CREATED) 회차만 취소 가능
     */
    public void cancel() {
        transit(ShipmentStatus.CREATED, ShipmentStatus.CANCELED);
    }

    public boolean isInstructed() {
        return status != ShipmentStatus.CREATED && status != ShipmentStatus.CANCELED;
    }

    private void transit(ShipmentStatus expected, ShipmentStatus next) {
        requireStatus(expected, next.name());
        this.status = next;
    }

    private void requireStatus(ShipmentStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException(
                    "출고 회차 상태 전이 불가. shipmentNo=" + shipmentNo + ", status=" + status + ", action=" + action);
        }
    }
}
