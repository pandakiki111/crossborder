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

    /** 조회 스코프용 (멀티브랜드여도 회사는 단일) */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

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
                  String receiverAddress, String deliveryMemo, String marketMemo, LocalDateTime orderedAt) {
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
    }

    public void changeReceiver(String receiverName, String receiverPhone, String receiverZipcode,
                               String receiverAddress, String deliveryMemo) {
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
     * 일부 항목 취소 반영. SHIPPING 주문도 CREATED 회차 항목은 취소할 수 있으므로 허용하고 상태는 유지한다.
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
     * 첫 회차 INSTRUCTED 시점에 호출. 이미 SHIPPING이면 무시.
     */
    public void startShipping() {
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
