package com.crossborder.oms.dto.order;

import com.crossborder.common.entity.order.OrderStatus;
import java.util.List;

/**
 * @param canceledShipmentIds 취소로 비어서 CANCELED된 회차
 */
public record CancellationResponse(
        Long orderId,
        OrderStatus orderStatus,
        List<CanceledItem> items,
        List<Long> canceledShipmentIds
) {

    /**
     * @param canceledOrderItemId 부분취소면 분할된 CANCELED 행의 id, 전체 취소면 원래 항목 id
     */
    public record CanceledItem(Long orderItemId, int canceledQuantity, Long canceledOrderItemId) {
    }
}
