package com.crossborder.oms.controller;

import com.crossborder.oms.dto.product.ProductBasicInfoRequest;
import com.crossborder.oms.dto.product.ProductCreateRequest;
import com.crossborder.oms.dto.product.ProductCustomsInfoRequest;
import com.crossborder.oms.dto.product.ProductDimensionsRequest;
import com.crossborder.oms.dto.product.ProductResponse;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.product.ProductService;
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
 * 제품(SKU) 관리 — ADMIN·COMPANY_STAFF (SecurityConfig). 수정은 엔티티 변경 단위별 엔드포인트.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductCreateRequest request,
                                                  @AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request, user));
    }

    @GetMapping
    public List<ProductResponse> list(@RequestParam(required = false) Long brandId,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return productService.list(brandId, user);
    }

    @GetMapping("/{productId}")
    public ProductResponse get(@PathVariable Long productId, @AuthenticationPrincipal AuthenticatedUser user) {
        return productService.get(productId, user);
    }

    @PutMapping("/{productId}/basic-info")
    public ProductResponse changeBasicInfo(@PathVariable Long productId, @Valid @RequestBody ProductBasicInfoRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return productService.changeBasicInfo(productId, request, user);
    }

    @PutMapping("/{productId}/customs-info")
    public ProductResponse changeCustomsInfo(@PathVariable Long productId,
                                             @Valid @RequestBody ProductCustomsInfoRequest request,
                                             @AuthenticationPrincipal AuthenticatedUser user) {
        return productService.changeCustomsInfo(productId, request, user);
    }

    @PutMapping("/{productId}/dimensions")
    public ProductResponse changeDimensions(@PathVariable Long productId, @RequestBody ProductDimensionsRequest request,
                                            @AuthenticationPrincipal AuthenticatedUser user) {
        return productService.changeDimensions(productId, request, user);
    }

    /** 구성에 포함한 ACTIVE 판매상품이 있으면 409 + 목록 */
    @PostMapping("/{productId}/deactivate")
    public ProductResponse deactivate(@PathVariable Long productId, @AuthenticationPrincipal AuthenticatedUser user) {
        return productService.deactivate(productId, user);
    }

    @PostMapping("/{productId}/activate")
    public ProductResponse activate(@PathVariable Long productId, @AuthenticationPrincipal AuthenticatedUser user) {
        return productService.activate(productId, user);
    }
}
