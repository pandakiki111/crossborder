package com.crossborder.common.entity.order;

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
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 주문/출고 상태 변경 이력 (append-only)
 * <p>
 * ORDER / SHIPMENT 상태를 함께 담으므로 상태값은 문자열로 저장한다.
 */
@Getter
@Entity
@Table(name = "order_status_history")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 주문 (SHIPMENT 변경도 주문 축으로 조회) */
    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private StatusTargetType targetType;

    /** orders.id 또는 shipments.id (이형 참조, FK 없음) */
    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Column(name = "previous_status", length = 20, updatable = false)
    private String previousStatus;

    @Column(name = "current_status", nullable = false, length = 20, updatable = false)
    private String currentStatus;

    /** 변경자 (시스템 전이는 SYSTEM 계정) */
    @Column(name = "changed_by", nullable = false, updatable = false)
    private Long changedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private OrderStatusHistory(Long orderId, StatusTargetType targetType, Long targetId,
                               String previousStatus, String currentStatus, Long changedBy) {
        this.orderId = orderId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.previousStatus = previousStatus;
        this.currentStatus = currentStatus;
        this.changedBy = changedBy;
    }

    /**
     * @param previousStatus 최초 생성이면 null
     */
    public static OrderStatusHistory ofOrder(Order order, OrderStatus previousStatus, Long changedBy) {
        Objects.requireNonNull(order.getId(), "저장되지 않은 주문의 이력은 남길 수 없습니다.");
        return new OrderStatusHistory(order.getId(), StatusTargetType.ORDER, order.getId(),
                nameOf(previousStatus), order.getStatus().name(), changedBy);
    }

    /**
     * @param previousStatus 최초 생성이면 null
     */
    public static OrderStatusHistory ofShipment(Shipment shipment, ShipmentStatus previousStatus, Long changedBy) {
        Objects.requireNonNull(shipment.getId(), "저장되지 않은 회차의 이력은 남길 수 없습니다.");
        return new OrderStatusHistory(shipment.getOrderId(), StatusTargetType.SHIPMENT, shipment.getId(),
                nameOf(previousStatus), shipment.getStatus().name(), changedBy);
    }

    private static String nameOf(Enum<?> status) {
        return status == null ? null : status.name();
    }
}
