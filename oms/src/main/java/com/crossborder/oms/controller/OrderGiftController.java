package com.crossborder.oms.controller;

import com.crossborder.oms.dto.order.GiftAddRequest;
import com.crossborder.oms.dto.order.GiftAddResponse;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.order.OrderGiftService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 수동 증정 (사은품 제품 추가)
 */
@RestController
@RequestMapping("/api/orders/{orderId}/gifts")
public class OrderGiftController {

    private final OrderGiftService orderGiftService;

    public OrderGiftController(OrderGiftService orderGiftService) {
        this.orderGiftService = orderGiftService;
    }

    /**
     * 수동 증정 (gift_source=MANUAL). 취소는 주문 취소 API로 (사은품은 전체 취소만).
     */
    @PostMapping
    public ResponseEntity<GiftAddResponse> addGift(@PathVariable Long orderId,
                                                   @Valid @RequestBody GiftAddRequest request,
                                                   @AuthenticationPrincipal AuthenticatedUser user) {
        Long orderItemId = orderGiftService.addGift(orderId, request, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(new GiftAddResponse(orderItemId));
    }
}
