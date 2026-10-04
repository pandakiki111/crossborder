package com.crossborder.oms.controller;

import com.crossborder.oms.dto.order.CancellationItemRequest;
import com.crossborder.oms.dto.order.CancellationResponse;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.order.OrderCancellationService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자 수동 취소 (주문리스트·상세). 마켓 수집발 취소는 수집 API와 함께 별도로 다룬다.
 */
@RestController
@RequestMapping("/api/orders/{orderId}/cancellations")
public class OrderCancellationController {

    private final OrderCancellationService orderCancellationService;

    public OrderCancellationController(OrderCancellationService orderCancellationService) {
        this.orderCancellationService = orderCancellationService;
    }

    /**
     * body: [{"orderItemId": 1, "quantity": 2}, ...]. 한 항목이라도 거부되면 전체 거부
     * (형식 400 / 타 브랜드 항목 403 / 이미 취소·취소 가능 수량 초과 409).
     */
    @PostMapping
    public CancellationResponse cancel(@PathVariable Long orderId, @RequestBody List<CancellationItemRequest> items,
                                       @AuthenticationPrincipal AuthenticatedUser user) {
        return orderCancellationService.cancel(orderId, items, user);
    }
}
