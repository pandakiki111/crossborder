package com.crossborder.oms.controller;

import com.crossborder.oms.dto.order.OrderSplitRequest;
import com.crossborder.oms.dto.order.ShipmentResponse;
import com.crossborder.oms.dto.order.SplitPreviewResponse;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.order.OrderSplitService;
import jakarta.validation.Valid;
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
 * 주문 분리 (출고 회차 생성). 미리보기로 자동 제안을 받고, 그대로 또는 고쳐서 확정한다.
 */
@RestController
@RequestMapping("/api/orders/{orderId}/split")
public class OrderSplitController {

    private final OrderSplitService orderSplitService;

    public OrderSplitController(OrderSplitService orderSplitService) {
        this.orderSplitService = orderSplitService;
    }

    @GetMapping("/preview")
    public SplitPreviewResponse preview(@PathVariable Long orderId, @AuthenticationPrincipal AuthenticatedUser user) {
        return orderSplitService.preview(orderId, user);
    }

    @PostMapping
    public ResponseEntity<List<ShipmentResponse>> split(@PathVariable Long orderId,
                                                        @Valid @RequestBody OrderSplitRequest request,
                                                        @AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderSplitService.split(orderId, request, user));
    }
}
