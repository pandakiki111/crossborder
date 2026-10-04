package com.crossborder.oms.dto.order;

import com.crossborder.common.entity.order.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 주문 목록 행
 *
 * @param itemCount         유효(ORDERED) 항목 수
 * @param mappingPending    매핑안됨 주문 (분리·출고 대상 아님)
 * @param unmappedItemCount 매핑안됨 항목 수 (mappingPending 주문만 0보다 큼)
 */
public record OrderSummaryResponse(
        Long orderId,
        String orderNo,
        String salesChannelCode,
        String channelOrderNo,
        OrderStatus status,
        boolean mappingPending,
        String ordererName,
        String receiverName,
        BigDecimal paidAmount,
        String currency,
        long itemCount,
        long unmappedItemCount,
        LocalDateTime orderedAt
) {
}
