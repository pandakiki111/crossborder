package com.crossborder.oms.dto.order;

import com.crossborder.common.entity.order.OrderItemStatus;
import com.crossborder.common.entity.order.OrderItemType;
import com.crossborder.common.entity.order.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 상세: 주문 정보 + 항목(구매·사은품) + 출고 회차
 */
public record OrderDetailResponse(
        Long orderId,
        String orderNo,
        Long salesChannelId,
        String salesChannelCode,
        String channelOrderNo,
        Long companyId,
        OrderStatus status,
        BigDecimal totalItemAmount,
        BigDecimal paidAmount,
        String currency,
        String ordererName,
        Receiver receiver,
        String marketMemo,
        LocalDateTime orderedAt,
        List<Item> items,
        List<ShipmentResponse> shipments
) {

    public record Receiver(String name, String phone, String zipcode, String address, String deliveryMemo) {
    }

    /**
     * @param saleProductId      itemType=SALE_PRODUCT일 때. 매핑안됨이면 null
     * @param productId          itemType=GIFT_PRODUCT일 때
     * @param channelProductCode 채널 수신 상품코드 (매핑안됨 항목 확인용)
     * @param channelOptionCode  채널 수신 옵션코드 (옵션 없음 = '')
     * @param name               판매상품명 또는 제품명. 매핑안됨이면 null
     */
    public record Item(
            Long orderItemId,
            OrderItemType itemType,
            Long brandId,
            Long saleProductId,
            Long productId,
            String channelProductCode,
            String channelOptionCode,
            boolean mapped,
            String name,
            int quantity,
            BigDecimal unitPrice,
            OrderItemStatus status
    ) {
    }
}
