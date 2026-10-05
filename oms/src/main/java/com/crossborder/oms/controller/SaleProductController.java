package com.crossborder.oms.controller;

import com.crossborder.oms.dto.product.CompositionRequest;
import com.crossborder.oms.dto.product.SaleProductCreateRequest;
import com.crossborder.oms.dto.product.SaleProductRenameRequest;
import com.crossborder.oms.dto.product.SaleProductRenewRequest;
import com.crossborder.oms.dto.product.SaleProductRenewResponse;
import com.crossborder.oms.dto.product.SaleProductResponse;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.product.SaleProductService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 판매상품 관리 — ADMIN·COMPANY_STAFF·BRAND_STAFF(자기 브랜드). 등록·구성 변경·리뉴얼 응답의 warnings는 사전 경고.
 */
@RestController
@RequestMapping("/api/sale-products")
public class SaleProductController {

    private final SaleProductService saleProductService;

    public SaleProductController(SaleProductService saleProductService) {
        this.saleProductService = saleProductService;
    }

    @PostMapping
    public ResponseEntity<SaleProductResponse> create(@Valid @RequestBody SaleProductCreateRequest request,
                                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(saleProductService.create(request, user));
    }

    @GetMapping
    public List<SaleProductResponse> list(@RequestParam(required = false) Long brandId,
                                          @AuthenticationPrincipal AuthenticatedUser user) {
        return saleProductService.list(brandId, user);
    }

    @GetMapping("/{saleProductId}")
    public SaleProductResponse get(@PathVariable Long saleProductId, @AuthenticationPrincipal AuthenticatedUser user) {
        return saleProductService.get(saleProductId, user);
    }

    @PutMapping("/{saleProductId}")
    public SaleProductResponse rename(@PathVariable Long saleProductId, @Valid @RequestBody SaleProductRenameRequest request,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return saleProductService.rename(saleProductId, request.name(), user);
    }

    /** 주문 이력이 있으면 409 (리뉴얼 안내) */
    @PutMapping("/{saleProductId}/composition")
    public SaleProductResponse changeComposition(@PathVariable Long saleProductId,
                                                 @Valid @RequestBody CompositionRequest request,
                                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return saleProductService.changeComposition(saleProductId, request.items(), user);
    }

    /** 새 판매상품 생성 + 현재 채널 매핑 일괄 재지정 + 구 상품 비활성화 (한 트랜잭션) */
    @PostMapping("/{saleProductId}/renew")
    public ResponseEntity<SaleProductRenewResponse> renew(@PathVariable Long saleProductId,
                                                          @Valid @RequestBody SaleProductRenewRequest request,
                                                          @AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(saleProductService.renew(saleProductId, request, user));
    }

    @PostMapping("/{saleProductId}/deactivate")
    public SaleProductResponse deactivate(@PathVariable Long saleProductId, @AuthenticationPrincipal AuthenticatedUser user) {
        return saleProductService.deactivate(saleProductId, user);
    }

    @PostMapping("/{saleProductId}/activate")
    public SaleProductResponse activate(@PathVariable Long saleProductId, @AuthenticationPrincipal AuthenticatedUser user) {
        return saleProductService.activate(saleProductId, user);
    }
}
