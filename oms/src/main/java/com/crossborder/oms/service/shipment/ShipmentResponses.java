package com.crossborder.oms.service.shipment;

import com.crossborder.common.entity.order.Shipment;
import com.crossborder.common.entity.order.ShipmentItem;
import com.crossborder.oms.dto.shipment.BulkResult;
import com.crossborder.oms.dto.shipment.ShipmentResponse;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.ForbiddenException;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.infra.lock.LockAcquisitionException;
import com.crossborder.oms.repository.ShipmentItemRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 회차 응답 조립과 일괄 처리 실패 분류. 경고는 저장하지 않고 조회 시점에 계산한다 (기준이 바뀌어도 재계산 불요).
 */
@Component
public class ShipmentResponses {

    /** 일괄 API 한 번에 받는 최대 대상 수 */
    public static final int MAX_BULK = 500;

    private final ShipmentItemRepository shipmentItemRepository;
    private final int warnThreshold;

    public ShipmentResponses(ShipmentItemRepository shipmentItemRepository,
                             @Value("${crossborder.customs.quantity-warn-threshold:24}") int warnThreshold) {
        this.shipmentItemRepository = shipmentItemRepository;
        this.warnThreshold = warnThreshold;
    }

    public List<ShipmentResponse> of(Collection<Shipment> shipments) {
        Map<Long, List<ShipmentItem>> items = shipments.isEmpty() ? Map.of()
                : shipmentItemRepository.findByShipmentIdIn(shipments.stream().map(Shipment::getId).toList()).stream()
                .collect(Collectors.groupingBy(ShipmentItem::getShipmentId));
        return shipments.stream().map(s -> of(s, items.getOrDefault(s.getId(), List.of()))).toList();
    }

    public ShipmentResponse of(Shipment shipment, List<ShipmentItem> items) {
        int totalQuantity = items.stream().mapToInt(ShipmentItem::getQuantity).sum();
        return new ShipmentResponse(shipment.getId(), shipment.getShipmentNo(), shipment.getRoundNo(),
                shipment.getBrandId(), shipment.getStatus(), shipment.getSplitReason(), shipment.getTotalAmount(),
                items.stream().map(i -> new ShipmentResponse.Item(i.getOrderItemId(), i.getQuantity())).toList(),
                ShipmentPlanner.warnings(totalQuantity, warnThreshold));
    }

    static void requireBulkSize(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new InvalidRequestException("대상 id가 없습니다.");
        }
        if (ids.size() > MAX_BULK) {
            throw new InvalidRequestException("한 번에 최대 " + MAX_BULK + "건까지 처리할 수 있습니다. 요청=" + ids.size());
        }
    }

    static <T> BulkResult.Entry<T> failure(Long id, RuntimeException e) {
        String error = switch (e) {
            case InvalidRequestException ignored -> "BAD_REQUEST";
            case ForbiddenException ignored -> "FORBIDDEN";
            case NotFoundException ignored -> "NOT_FOUND";
            case ConflictException ignored -> "CONFLICT";
            case IllegalStateException ignored -> "CONFLICT";
            case LockAcquisitionException ignored -> "CONFLICT";
            default -> "ERROR";
        };
        return new BulkResult.Entry<>(id, false, null, error, e.getMessage());
    }
}
