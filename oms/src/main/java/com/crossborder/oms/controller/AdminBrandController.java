package com.crossborder.oms.controller;

import com.crossborder.oms.dto.organization.BrandCreateRequest;
import com.crossborder.oms.dto.organization.BrandResponse;
import com.crossborder.oms.dto.organization.BrandUpdateRequest;
import com.crossborder.oms.dto.organization.TerminationResponse;
import com.crossborder.oms.service.organization.BrandAdminService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브랜드 관리 (ADMIN 전용 — SecurityConfig /api/admin/**).
 */
@RestController
@RequestMapping("/api/admin/brands")
public class AdminBrandController {

    private final BrandAdminService brandAdminService;

    public AdminBrandController(BrandAdminService brandAdminService) {
        this.brandAdminService = brandAdminService;
    }

    @PostMapping
    public ResponseEntity<BrandResponse> create(@Valid @RequestBody BrandCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(brandAdminService.create(request));
    }

    @GetMapping
    public List<BrandResponse> list(@RequestParam(required = false) Long companyId) {
        return brandAdminService.list(companyId);
    }

    @GetMapping("/{brandId}")
    public BrandResponse get(@PathVariable Long brandId) {
        return brandAdminService.get(brandId);
    }

    @PutMapping("/{brandId}")
    public BrandResponse update(@PathVariable Long brandId, @Valid @RequestBody BrandUpdateRequest request) {
        return brandAdminService.rename(brandId, request.name());
    }

    @PostMapping("/{brandId}/deactivate")
    public BrandResponse deactivate(@PathVariable Long brandId) {
        return brandAdminService.deactivate(brandId);
    }

    @PostMapping("/{brandId}/activate")
    public BrandResponse activate(@PathVariable Long brandId) {
        return brandAdminService.activate(brandId);
    }

    /** 계약종료 (불가역). confirm = 브랜드명 그대로 */
    @PostMapping("/{brandId}/terminate")
    public TerminationResponse terminate(@PathVariable Long brandId, @RequestParam String confirm) {
        return brandAdminService.terminate(brandId, confirm);
    }
}
