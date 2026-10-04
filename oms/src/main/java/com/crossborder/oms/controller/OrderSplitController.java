package com.crossborder.oms.controller;

import com.crossborder.oms.dto.shipment.BulkResult;
import com.crossborder.oms.dto.shipment.BulkSplitRequest;
import com.crossborder.oms.dto.shipment.ShipmentResponse;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.shipment.OrderSplitService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 분리 (출고 회차 생성)와 분리 결과 조회.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderSplitController {

    private final OrderSplitService orderSplitService;

    public OrderSplitController(OrderSplitService orderSplitService) {
        this.orderSplitService = orderSplitService;
    }

    /** 단건 분리. CREATED 회차만 있으면 재분리 (기존 CREATED 회차는 CANCELED) */
    @PostMapping("/{orderId}/splits")
    public ResponseEntity<List<ShipmentResponse>> split(@PathVariable Long orderId,
                                                        @AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderSplitService.split(orderId, user));
    }

    /** 일괄 분리 (orderIds 명시, 최대 500건). 주문별 성공/실패 요약 */
    @PostMapping("/splits")
    public BulkResult<List<ShipmentResponse>> splitAll(@RequestBody BulkSplitRequest request,
                                                       @AuthenticationPrincipal AuthenticatedUser user) {
        return orderSplitService.splitAll(request.orderIds(), user);
    }

    /** 분리 결과 조회 (취소된 회차 포함, 경고는 조회 시 계산) */
    @GetMapping("/{orderId}/shipments")
    public List<ShipmentResponse> shipments(@PathVariable Long orderId) {
        return orderSplitService.shipments(orderId);
    }
}
