package com.crossborder.common.entity.order;

import com.crossborder.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회차별 출고 항목 (어느 주문 항목 몇 개가 이 회차인지)
 */
@Getter
@Entity
@Table(name = "shipment_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShipmentItem extends BaseAuditEntity {

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private Long shipmentId;

    @Column(name = "order_item_id", nullable = false, updatable = false)
    private Long orderItemId;

    @Column(nullable = false)
    private int quantity;

    private ShipmentItem(Long shipmentId, OrderItem orderItem, int quantity) {
        Objects.requireNonNull(shipmentId, "저장되지 않은 회차에 항목을 담을 수 없습니다.");
        Objects.requireNonNull(orderItem.getId(), "저장되지 않은 주문 항목은 회차에 담을 수 없습니다.");
        if (!orderItem.isOrdered()) {
            throw new IllegalStateException("취소된 주문 항목은 회차에 담을 수 없습니다. orderItemId=" + orderItem.getId());
        }
        validateQuantity(orderItem, quantity);
        this.shipmentId = shipmentId;
        this.orderItemId = orderItem.getId();
        this.quantity = quantity;
    }

    /**
     * 수량 검증을 위해 OrderItem을 받는다.
     */
    public static ShipmentItem create(Long shipmentId, OrderItem orderItem, int quantity) {
        return new ShipmentItem(shipmentId, orderItem, quantity);
    }

    public void changeQuantity(OrderItem orderItem, int quantity) {
        if (!orderItemId.equals(orderItem.getId())) {
            throw new IllegalArgumentException("이 회차 항목의 주문 항목이 아닙니다. orderItemId=" + orderItem.getId());
        }
        validateQuantity(orderItem, quantity);
        this.quantity = quantity;
    }

    private static void validateQuantity(OrderItem orderItem, int quantity) {
        if (quantity <= 0 || quantity > orderItem.getQuantity()) {
            throw new IllegalArgumentException(
                    "회차 수량은 1 이상, 주문 항목 수량(" + orderItem.getQuantity() + ") 이하여야 합니다. quantity=" + quantity);
        }
    }
}
