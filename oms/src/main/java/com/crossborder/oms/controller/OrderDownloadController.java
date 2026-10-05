package com.crossborder.oms.controller;

import com.crossborder.oms.dto.order.OrderDownloadEstimate;
import com.crossborder.oms.dto.order.OrderDownloadRequest;
import com.crossborder.oms.dto.order.OrderSearchCondition;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.order.download.OrderDownloadService;
import com.crossborder.oms.service.order.download.OrderDownloadService.DownloadSink;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 목록 엑셀 다운로드. 조건은 주문 목록(GET /api/orders)과 같고 기간은 필수(최대 설정 일수).
 * 응답 헤더 X-Download-Count = 데이터 행 수. 같은 사용자의 다운로드가 진행 중이면 409.
 */
@RestController
@RequestMapping("/api/orders/download")
public class OrderDownloadController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final OrderDownloadService orderDownloadService;

    public OrderDownloadController(OrderDownloadService orderDownloadService) {
        this.orderDownloadService = orderDownloadService;
    }

    /**
     * 예: GET /api/orders/download?orderedFrom=2026-10-01&orderedTo=2026-10-07&status=PAID&columns=ORDER_NO&columns=SKU
     */
    @GetMapping
    public void download(@ModelAttribute OrderSearchCondition condition,
                         @RequestParam(required = false) List<String> columns,
                         @AuthenticationPrincipal AuthenticatedUser user,
                         HttpServletResponse response) {
        orderDownloadService.download(condition, columns, user, sink(response));
    }

    /** 조건이 긴 경우(주문번호 복수 등) 본문으로 받는다 */
    @PostMapping
    public void downloadByBody(@RequestBody OrderDownloadRequest request,
                               @AuthenticationPrincipal AuthenticatedUser user,
                               HttpServletResponse response) {
        orderDownloadService.download(conditionOf(request), request.columns(), user, sink(response));
    }

    /** 다운로드 전 주문 수 (상한까지만 센다) */
    @GetMapping("/estimate")
    public OrderDownloadEstimate estimate(@ModelAttribute OrderSearchCondition condition,
                                          @AuthenticationPrincipal AuthenticatedUser user) {
        return orderDownloadService.estimate(condition, user);
    }

    @PostMapping("/estimate")
    public OrderDownloadEstimate estimateByBody(@RequestBody OrderSearchCondition condition,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        return orderDownloadService.estimate(condition, user);
    }

    /** 생성이 끝난 뒤에야 열린다 — 그 전 오류는 응답이 커밋되지 않아 GlobalExceptionHandler가 4xx/5xx로 돌려준다 */
    private static DownloadSink sink(HttpServletResponse response) {
        return (fileName, rowCount) -> {
            response.setContentType(XLSX);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString());
            response.setHeader("X-Download-Count", String.valueOf(rowCount));
            return response.getOutputStream();
        };
    }

    private static OrderSearchCondition conditionOf(OrderDownloadRequest request) {
        return request.condition() != null ? request.condition() : OrderSearchCondition.empty();
    }
}
