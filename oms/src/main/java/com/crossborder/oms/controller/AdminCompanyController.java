package com.crossborder.oms.controller;

import com.crossborder.oms.dto.organization.CompanyRequest;
import com.crossborder.oms.dto.organization.CompanyResponse;
import com.crossborder.oms.dto.organization.TerminationResponse;
import com.crossborder.oms.service.organization.CompanyAdminService;
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
 * 회사 관리 (ADMIN 전용 — SecurityConfig /api/admin/**).
 */
@RestController
@RequestMapping("/api/admin/companies")
public class AdminCompanyController {

    private final CompanyAdminService companyAdminService;

    public AdminCompanyController(CompanyAdminService companyAdminService) {
        this.companyAdminService = companyAdminService;
    }

    @PostMapping
    public ResponseEntity<CompanyResponse> create(@Valid @RequestBody CompanyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(companyAdminService.create(request));
    }

    @GetMapping
    public List<CompanyResponse> list() {
        return companyAdminService.list();
    }

    @GetMapping("/{companyId}")
    public CompanyResponse get(@PathVariable Long companyId) {
        return companyAdminService.get(companyId);
    }

    @PutMapping("/{companyId}")
    public CompanyResponse update(@PathVariable Long companyId, @Valid @RequestBody CompanyRequest request) {
        return companyAdminService.update(companyId, request);
    }

    @PostMapping("/{companyId}/deactivate")
    public CompanyResponse deactivate(@PathVariable Long companyId) {
        return companyAdminService.deactivate(companyId);
    }

    @PostMapping("/{companyId}/activate")
    public CompanyResponse activate(@PathVariable Long companyId) {
        return companyAdminService.activate(companyId);
    }

    /** 계약종료 (불가역). confirm = 회사명 그대로 */
    @PostMapping("/{companyId}/terminate")
    public TerminationResponse terminate(@PathVariable Long companyId, @RequestParam String confirm) {
        return companyAdminService.terminate(companyId, confirm);
    }
}
