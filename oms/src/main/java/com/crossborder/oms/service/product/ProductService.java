package com.crossborder.oms.service.product;

import com.crossborder.common.entity.ActiveStatus;
import com.crossborder.common.entity.product.Product;
import com.crossborder.common.entity.product.SaleProduct;
import com.crossborder.oms.dto.product.ProductBasicInfoRequest;
import com.crossborder.oms.dto.product.ProductCreateRequest;
import com.crossborder.oms.dto.product.ProductCustomsInfoRequest;
import com.crossborder.oms.dto.product.ProductDimensionsRequest;
import com.crossborder.oms.dto.product.ProductResponse;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.ForbiddenException;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.oms.repository.CustomsCategoryRepository;
import com.crossborder.oms.repository.ProductRepository;
import com.crossborder.oms.repository.SaleProductRepository;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.security.scope.ScopePolicy;
import com.crossborder.oms.service.support.BrandWriteGuard;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제품(SKU) 관리 — ADMIN·COMPANY_STAFF(자사 브랜드). BRAND_STAFF는 자기 브랜드 조회만 (판매상품 구성용, 쓰기는 SecurityConfig에서 403).
 * <ul>
 *   <li>sku는 전역 유니크·불변. 수정은 엔티티 변경 단위(기본정보·통관정보·치수)로만</li>
 *   <li>비활성화: 이 제품을 구성에 포함한 ACTIVE 판매상품이 있으면 409 + 목록 (판매상품부터 정리)</li>
 *   <li>비활성 브랜드: 등록·수정·활성화 409, 비활성화는 정리 행위라 허용 (BrandWriteGuard)</li>
 * </ul>
 * 재고(physical·allocated)는 여기서 바꾸지 않는다 (원장·할당 경로 전용).
 */
@Service
@Transactional
public class ProductService {

    private final ProductRepository productRepository;
    private final SaleProductRepository saleProductRepository;
    private final CustomsCategoryRepository customsCategoryRepository;
    private final ScopePolicy scopePolicy;
    private final BrandWriteGuard brandWriteGuard;

    public ProductService(ProductRepository productRepository, SaleProductRepository saleProductRepository,
                          CustomsCategoryRepository customsCategoryRepository, ScopePolicy scopePolicy,
                          BrandWriteGuard brandWriteGuard) {
        this.productRepository = productRepository;
        this.saleProductRepository = saleProductRepository;
        this.customsCategoryRepository = customsCategoryRepository;
        this.scopePolicy = scopePolicy;
        this.brandWriteGuard = brandWriteGuard;
    }

    /**
     * @throws ForbiddenException 스코프 밖 브랜드
     * @throws ConflictException  비활성 브랜드 / sku 중복
     */
    public ProductResponse create(ProductCreateRequest r, AuthenticatedUser user) {
        scopePolicy.requireBrand(r.brandId(), user);
        brandWriteGuard.requireWritable(r.brandId());
        requireCategory(r.customsCategoryId());
        String sku = r.sku().trim();
        if (productRepository.existsBySku(sku)) {
            throw new ConflictException("이미 있는 SKU입니다: " + sku);
        }
        Product product = Product.builder()
                .brandId(r.brandId()).sku(sku).name(r.name().trim()).nameEng(r.nameEng().trim())
                .customsCategoryId(r.customsCategoryId()).hsCode(r.hsCode()).unitPrice(r.unitPrice())
                .currency(r.currency()).barcode(r.barcode()).weightG(r.weightG()).widthCm(r.widthCm())
                .lengthCm(r.lengthCm()).heightCm(r.heightCm()).origin(r.origin())
                .build();
        if (r.customsUnitQty() != null) {
            product.changeCustomsUnitQty(r.customsUnitQty());
        }
        return ProductResponse.from(productRepository.saveAndFlush(product));
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Long productId, AuthenticatedUser user) {
        return ProductResponse.from(require(productId, user));
    }

    /** brandId 없으면 스코프 안 전체 */
    @Transactional(readOnly = true)
    public List<ProductResponse> list(Long brandId, AuthenticatedUser user) {
        List<Product> products;
        if (brandId != null) {
            scopePolicy.requireBrand(brandId, user);
            products = productRepository.findByBrandIdInOrderByIdAsc(List.of(brandId));
        } else {
            products = scopePolicy.accessibleBrandIds(user)
                    .map(productRepository::findByBrandIdInOrderByIdAsc)
                    .orElseGet(productRepository::findAllByOrderByIdAsc);
        }
        return products.stream().map(ProductResponse::from).toList();
    }

    public ProductResponse changeBasicInfo(Long productId, ProductBasicInfoRequest r, AuthenticatedUser user) {
        return modify(productId, user, p -> p.changeBasicInfo(r.name().trim(), r.nameEng().trim(), r.barcode(),
                r.unitPrice(), r.currency()));
    }

    public ProductResponse changeCustomsInfo(Long productId, ProductCustomsInfoRequest r, AuthenticatedUser user) {
        requireCategory(r.customsCategoryId());
        return modify(productId, user, p -> {
            p.changeCustomsInfo(r.customsCategoryId(), r.hsCode(), r.origin());
            p.changeCustomsUnitQty(r.customsUnitQty());
        });
    }

    public ProductResponse changeDimensions(Long productId, ProductDimensionsRequest r, AuthenticatedUser user) {
        return modify(productId, user, p -> p.changeDimensions(r.weightG(), r.widthCm(), r.lengthCm(), r.heightCm()));
    }

    /**
     * @throws ConflictException 이 제품을 구성에 포함한 ACTIVE 판매상품 있음
     */
    public ProductResponse deactivate(Long productId, AuthenticatedUser user) {
        Product product = require(productId, user);
        List<SaleProduct> active = saleProductRepository.findContainingProduct(productId, ActiveStatus.ACTIVE);
        if (!active.isEmpty()) {
            throw new ConflictException("이 제품을 구성에 포함한 ACTIVE 판매상품이 있어 비활성화할 수 없습니다: "
                    + active.stream().map(SaleProduct::getCode).collect(Collectors.joining(", ")));
        }
        product.deactivate();
        return ProductResponse.from(product);
    }

    public ProductResponse activate(Long productId, AuthenticatedUser user) {
        return modify(productId, user, Product::activate);
    }

    /** 수정 계열 공통: 스코프 → 쓰기 가능 브랜드 → 변경 (엔티티 규칙 위반은 400) */
    private ProductResponse modify(Long productId, AuthenticatedUser user, Consumer<Product> change) {
        Product product = require(productId, user);
        brandWriteGuard.requireWritable(product.getBrandId());
        try {
            change.accept(product);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }
        productRepository.flush();
        return ProductResponse.from(product);
    }

    private void requireCategory(Long customsCategoryId) {
        if (customsCategoryId != null && !customsCategoryRepository.existsById(customsCategoryId)) {
            throw new InvalidRequestException("통관 분류가 없습니다. customsCategoryId=" + customsCategoryId);
        }
    }

    private Product require(Long productId, AuthenticatedUser user) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("제품을 찾을 수 없습니다. productId=" + productId));
        if (!scopePolicy.canAccessBrand(product.getBrandId(), user)) {
            throw new ForbiddenException("해당 제품에 대한 권한이 없습니다. productId=" + productId);
        }
        return product;
    }
}
