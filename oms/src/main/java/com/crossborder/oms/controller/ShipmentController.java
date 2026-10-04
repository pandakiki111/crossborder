package com.crossborder.oms.controller;

import com.crossborder.oms.dto.shipment.BulkInstructRequest;
import com.crossborder.oms.dto.shipment.BulkResult;
import com.crossborder.oms.dto.shipment.ShipmentResponse;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.shipment.ShipmentInstructService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 출고지시 (회차 단위). cbt 출고 접수 호출은 하지 않는다 — 출고 연동 Phase 몫.
 */
@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private final ShipmentInstructService shipmentInstructService;

    public ShipmentController(ShipmentInstructService shipmentInstructService) {
        this.shipmentInstructService = shipmentInstructService;
    }

    @PostMapping("/{shipmentId}/instruct")
    public ShipmentResponse instruct(@PathVariable Long shipmentId, @AuthenticationPrincipal AuthenticatedUser user) {
        return shipmentInstructService.instruct(shipmentId, user);
    }

    /** 일괄 지시 (shipmentIds, 최대 500건). 회차별 성공/실패 요약 */
    @PostMapping("/instruct")
    public BulkResult<ShipmentResponse> instructAll(@RequestBody BulkInstructRequest request,
                                                    @AuthenticationPrincipal AuthenticatedUser user) {
        return shipmentInstructService.instructAll(request.shipmentIds(), user);
    }
}
