package com.crossborder.oms.controller;

import com.crossborder.oms.dto.CappedPageResponse;
import com.crossborder.oms.dto.order.OrderDetailResponse;
import com.crossborder.oms.dto.order.OrderSearchCondition;
import com.crossborder.oms.dto.order.OrderSummaryResponse;
import com.crossborder.oms.dto.order.ReceiverUpdateRequest;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.order.OrderQueryService;
import com.crossborder.oms.service.order.OrderReceiverService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderQueryService orderQueryService;
    private final OrderReceiverService orderReceiverService;

    public OrderController(OrderQueryService orderQueryService, OrderReceiverService orderReceiverService) {
        this.orderQueryService = orderQueryService;
        this.orderReceiverService = orderReceiverService;
    }

    /**
     * 주문 목록. 예: GET /api/orders?status=PAID&status=PARTIAL_CANCELED&shipmentStatus=CREATED&unsplit=true
     * &orderedFrom=2026-10-01&orderedTo=2026-10-05&page=0&size=20
     * 정렬은 주문일시 최신순 고정 (sort 파라미터 무시). 건수는 상한까지만 (totalCapped면 "10,000+").
     * 제약 위반(페이지 크기·offset·기간·부분 일치 기간·주문번호 개수)은 400.
     */
    @GetMapping
    public CappedPageResponse<OrderSummaryResponse> search(
            @ModelAttribute OrderSearchCondition condition,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return orderQueryService.search(condition, pageable, user);
    }

    /**
     * 같은 검색을 본문으로 받는다 (페이지는 쿼리 파라미터). 주문번호 복수 검색(최대 500개, 엑셀 붙여넣기)은
     * 쿼리 문자열이 요청 헤더 한도(Tomcat 기본 8KB)를 넘으므로 이쪽을 쓴다.
     */
    @PostMapping("/search")
    public CappedPageResponse<OrderSummaryResponse> searchByBody(
            @RequestBody OrderSearchCondition condition,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return orderQueryService.search(condition, pageable, user);
    }

    @GetMapping("/{orderId}")
    public OrderDetailResponse getDetail(@PathVariable Long orderId, @AuthenticationPrincipal AuthenticatedUser user) {
        return orderQueryService.getDetail(orderId, user);
    }

    /**
     * 수취인 정보 수정 (이름·전화번호·우편번호·주소·배송메모). 출고지시 이후 주문은 409.
     */
    @PatchMapping("/{orderId}/receiver")
    public ResponseEntity<Void> changeReceiver(@PathVariable Long orderId,
                                               @Valid @RequestBody ReceiverUpdateRequest request) {
        orderReceiverService.changeReceiver(orderId, request);
        return ResponseEntity.noContent().build();
    }
}
