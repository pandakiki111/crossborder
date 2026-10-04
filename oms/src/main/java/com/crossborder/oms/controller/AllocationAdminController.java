package com.crossborder.oms.controller;

import com.crossborder.oms.dto.stock.AllocationBackfillResponse;
import com.crossborder.oms.dto.stock.AllocationConsistencyResponse;
import com.crossborder.oms.service.stock.AllocationAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 재고 할당 관리 (ADMIN 전용 — SecurityConfig /api/admin/**). 수동 실행 커맨드이며 주기 실행은 하지 않는다.
 */
@RestController
@RequestMapping("/api/admin/allocations")
public class AllocationAdminController {

    private final AllocationAdminService allocationAdminService;

    public AllocationAdminController(AllocationAdminService allocationAdminService) {
        this.allocationAdminService = allocationAdminService;
    }

    /** 미할당 주문 소급 할당. 멱등 — 여러 번 실행해도 이중 할당되지 않는다 */
    @PostMapping("/backfill")
    public AllocationBackfillResponse backfill() {
        return allocationAdminService.backfill();
    }

    /** 등록-할당 정합 검증 (읽기 전용) */
    @GetMapping("/consistency")
    public AllocationConsistencyResponse consistency() {
        return allocationAdminService.checkConsistency();
    }
}
