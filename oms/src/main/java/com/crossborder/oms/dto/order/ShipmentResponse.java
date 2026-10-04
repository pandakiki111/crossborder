package com.crossborder.oms.dto.order;

import com.crossborder.common.entity.order.ShipmentStatus;
import com.crossborder.common.entity.order.SplitReason;
import java.math.BigDecimal;
import java.util.List;

/**
 * 출고 회차
 */
public record ShipmentResponse(
        Long shipmentId,
        String shipmentNo,
        int roundNo,
        Long brandId,
        ShipmentStatus status,
        SplitReason splitReason,
        BigDecimal totalAmount,
        List<Item> items
) {

    public record Item(Long orderItemId, int quantity) {
    }
}
