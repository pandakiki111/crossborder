package com.crossborder.oms.controller;

import com.crossborder.oms.dto.order.GiftAddRequest;
import com.crossborder.oms.dto.order.OrderDetailResponse;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.order.OrderGiftService;
import com.crossborder.oms.service.order.OrderQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 건별 사은품 매핑 (주문에 사은품 제품 추가·삭제)
 */
@RestController
@RequestMapping("/api/orders/{orderId}/gifts")
public class OrderGiftController {

    private final OrderGiftService orderGiftService;
    private final OrderQueryService orderQueryService;

    public OrderGiftController(OrderGiftService orderGiftService, OrderQueryService orderQueryService) {
        this.orderGiftService = orderGiftService;
        this.orderQueryService = orderQueryService;
    }

    /**
     * 사은품 추가 후 주문 상세를 돌려준다.
     */
    @PostMapping
    public ResponseEntity<OrderDetailResponse> addGift(@PathVariable Long orderId,
                                                       @Valid @RequestBody GiftAddRequest request,
                                                       @AuthenticationPrincipal AuthenticatedUser user) {
        orderGiftService.addGift(orderId, request, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(orderQueryService.getDetail(orderId, user));
    }

    /**
     * 사은품 항목 취소 (전체 취소만)
     */
    @DeleteMapping("/{orderItemId}")
    public ResponseEntity<Void> removeGift(@PathVariable Long orderId, @PathVariable Long orderItemId,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        orderGiftService.removeGift(orderId, orderItemId, user);
        return ResponseEntity.noContent().build();
    }
}
