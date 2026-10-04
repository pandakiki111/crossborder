package com.crossborder.common.entity.order;

import com.crossborder.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 (마켓 1주문 = 1행 불변, 분리해도 쪼개지 않음)
 */
@Getter
@Entity
@Table(name = "orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseAuditEntity {

    @Column(name = "order_no", nullable = false, length = 50, updatable = false)
    private String orderNo;

    @Column(name = "sales_channel_id", nullable = false, updatable = false)
    private Long salesChannelId;

    @Column(name = "channel_order_no", nullable = false, length = 100, updatable = false)
    private String channelOrderNo;

    /** 조회 스코프용 (한 주문 = 단일 브랜드 → 단일 회사) */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    /**
     * 판매상품 매핑 안 된 유효 항목이 있음. 상태와 독립 (PAID·PARTIAL_CANCELED 필터에 그대로 걸리도록).
     * true인 동안은 분리·출고 대상 아님.
     */
    @Column(name = "mapping_pending", nullable = false)
    private boolean mappingPending;

    /** 재고 할당 완료 시각 (null이면 미할당). 할당 멱등성과 등록-할당 정합 검증의 기준 */
    @Column(name = "allocated_at")
    private LocalDateTime allocatedAt;

    @Column(name = "total_item_amount", precision = 12, scale = 2)
    private BigDecimal totalItemAmount;

    @Column(name = "paid_amount", precision = 12, scale = 2)
    private BigDecimal paidAmount;

    @Column(columnDefinition = "CHAR(3)")
    private String currency;

    @Column(name = "orderer_name", nullable = false, length = 100)
    private String ordererName;

    @Column(name = "receiver_name", nullable = false, length = 100)
    private String receiverName;

    @Column(name = "receiver_phone", length = 30)
    private String receiverPhone;

    @Column(name = "receiver_zipcode", length = 10)
    private String receiverZipcode;

    @Column(name = "receiver_address", nullable = false, length = 500)
    private String receiverAddress;

    @Column(name = "delivery_memo", length = 300)
    private String deliveryMemo;

    @Column(name = "market_memo", length = 300)
    private String marketMemo;

    @Column(name = "ordered_at", nullable = false, updatable = false)
    private LocalDateTime orderedAt;

    @Builder
    private Order(String orderNo, Long salesChannelId, String channelOrderNo, Long companyId,
                  BigDecimal totalItemAmount, BigDecimal paidAmount, String currency,
                  String ordererName, String receiverName, String receiverPhone, String receiverZipcode,
                  String receiverAddress, String deliveryMemo, String marketMemo, LocalDateTime orderedAt,
                  boolean mappingPending) {
        this.orderNo = orderNo;
        this.salesChannelId = salesChannelId;
        this.channelOrderNo = channelOrderNo;
        this.companyId = companyId;
        this.totalItemAmount = totalItemAmount;
        this.paidAmount = paidAmount;
        this.currency = currency;
        this.ordererName = ordererName;
        this.receiverName = receiverName;
        this.receiverPhone = receiverPhone;
        this.receiverZipcode = receiverZipcode;
        this.receiverAddress = receiverAddress;
        this.deliveryMemo = deliveryMemo;
        this.marketMemo = marketMemo;
        this.orderedAt = orderedAt;
        this.status = OrderStatus.PAID;
        this.mappingPending = mappingPending;
    }

    /**
     * 수취인 정보 수정. 출고지시 이후(SHIPPING·DELIVERED)는 송장·라벨과 어긋나므로 불가.
     * SHIPPING은 첫 회차 INSTRUCTED 시점에 전이되므로 상태만으로 판정된다.
     */
    public void changeReceiver(String receiverName, String receiverPhone, String receiverZipcode,
                               String receiverAddress, String deliveryMemo) {
        if (status == OrderStatus.SHIPPING || status == OrderStatus.DELIVERED) {
            throw new IllegalStateException(
                    "출고지시 이후에는 수취인 정보를 수정할 수 없습니다. orderNo=" + orderNo + ", status=" + status);
        }
        this.receiverName = receiverName;
        this.receiverPhone = receiverPhone;
        this.receiverZipcode = receiverZipcode;
        this.receiverAddress = receiverAddress;
        this.deliveryMemo = deliveryMemo;
    }

    /**
     * 전체 취소 가능 여부. SHIPPING은 지시된 회차가 있다는 뜻이므로 불가.
     * <p>
     * 정확한 조건은 "모든 회차가 CREATED/CANCELED"이며, 회차 레벨 검증은 서비스 책임.
     */
    public boolean isCancelable() {
        return status == OrderStatus.PAID || status == OrderStatus.PARTIAL_CANCELED;
    }

    /**
     * 모든 유효 항목 매핑 완료. 확정 여부(ORDERED 매핑안됨 항목 없음)는 서비스 책임. 상태는 바뀌지 않는다.
     * <p>
     * 매핑안됨 항목 자체를 취소해서 남은 매핑안됨 항목이 없어진 경우에도 취소 처리 측에서 호출해야 한다.
     */
    public void completeMapping() {
        if (!mappingPending) {
            throw new IllegalStateException("매핑안됨 주문이 아닙니다. orderNo=" + orderNo);
        }
        this.mappingPending = false;
    }

    /**
     * 재고 할당 완료 기록. allocated_stock 반영과 같은 트랜잭션에서 호출한다 (서비스 책임).
     * 매핑안됨 주문은 전개할 수 없어 할당 대상이 아니다.
     */
    public void markAllocated(LocalDateTime at) {
        if (allocatedAt != null) {
            throw new IllegalStateException("이미 할당된 주문입니다. orderNo=" + orderNo + ", allocatedAt=" + allocatedAt);
        }
        if (mappingPending) {
            throw new IllegalStateException("매핑안됨 주문은 할당할 수 없습니다. orderNo=" + orderNo);
        }
        this.allocatedAt = at;
    }

    public boolean isAllocated() {
        return allocatedAt != null;
    }

    /**
     * 일부 항목 취소 반영. SHIPPING 주문도 CREATED 회차 항목은 취소할 수 있으므로 허용하고 상태는 유지한다.
     * 매핑안됨 주문도 같은 규칙 (PAID → PARTIAL_CANCELED).
     * <p>
     * 취소 대상 항목이 INSTRUCTED 이후 회차에 물려 있지 않은지는 서비스 책임.
     */
    public void cancelPartially() {
        switch (status) {
            case PAID -> this.status = OrderStatus.PARTIAL_CANCELED;
            case PARTIAL_CANCELED, SHIPPING -> { }
            default -> throw invalidTransition("부분취소");
        }
    }

    /**
     * 전체 취소. 회차 레벨 검증은 서비스 책임.
     */
    public void cancel() {
        if (!isCancelable()) {
            throw invalidTransition("전체취소");
        }
        this.status = OrderStatus.CANCELED;
    }

    /**
     * 첫 회차 INSTRUCTED 시점에 호출. 이미 SHIPPING이면 무시. 매핑안됨 주문은 회차가 없으므로 불가.
     */
    public void startShipping() {
        if (mappingPending) {
            throw invalidTransition("출고시작(매핑안됨)");
        }
        switch (status) {
            case PAID, PARTIAL_CANCELED -> this.status = OrderStatus.SHIPPING;
            case SHIPPING -> { }
            default -> throw invalidTransition("출고시작");
        }
    }

    public void deliver() {
        if (status != OrderStatus.SHIPPING) {
            throw invalidTransition("배송완료");
        }
        this.status = OrderStatus.DELIVERED;
    }

    private IllegalStateException invalidTransition(String action) {
        return new IllegalStateException(
                "주문 상태 전이 불가. orderNo=" + orderNo + ", status=" + status + ", action=" + action);
    }
}
