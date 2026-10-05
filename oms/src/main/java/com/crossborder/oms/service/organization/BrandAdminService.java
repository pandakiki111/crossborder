package com.crossborder.oms.service.organization;

import com.crossborder.common.entity.ActiveStatus;
import com.crossborder.common.entity.organization.Brand;
import com.crossborder.common.entity.organization.Company;
import com.crossborder.common.entity.product.SaleProduct;
import com.crossborder.oms.dto.organization.BrandCreateRequest;
import com.crossborder.oms.dto.organization.BrandResponse;
import com.crossborder.oms.dto.organization.TerminationResponse;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.oms.repository.BrandRepository;
import com.crossborder.oms.repository.CompanyRepository;
import com.crossborder.oms.repository.SaleProductRepository;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 브랜드 관리 (ADMIN 전용 — /api/admin/brands).
 * <ul>
 *   <li>비활성화·계약종료: ACTIVE 판매상품이 있으면 409 + 목록. 소속 사용자와는 무관하게 허용</li>
 *   <li>비활성(계약종료 포함) 브랜드: 쓰기 409, 조회·기존 주문 처리는 허용 (BrandWriteGuard)</li>
 *   <li>재활성화: 계약종료된 브랜드는 409 (Brand.activate 가드), 소속 회사가 비활성이면 409</li>
 *   <li>등록: 소속 회사가 활성이어야 한다. 브랜드명은 회사 안에서 유니크 (uk 위반 409)</li>
 * </ul>
 */
@Service
@Transactional
public class BrandAdminService {

    private final BrandRepository brandRepository;
    private final CompanyRepository companyRepository;
    private final SaleProductRepository saleProductRepository;

    public BrandAdminService(BrandRepository brandRepository, CompanyRepository companyRepository,
                             SaleProductRepository saleProductRepository) {
        this.brandRepository = brandRepository;
        this.companyRepository = companyRepository;
        this.saleProductRepository = saleProductRepository;
    }

    public BrandResponse create(BrandCreateRequest request) {
        Company company = companyRepository.findById(request.companyId())
                .orElseThrow(() -> new NotFoundException("회사를 찾을 수 없습니다. companyId=" + request.companyId()));
        requireActiveCompany(company);
        return BrandResponse.from(brandRepository.saveAndFlush(Brand.create(company.getId(), request.name().trim())));
    }

    public BrandResponse rename(Long brandId, String name) {
        Brand brand = require(brandId);
        brand.changeName(name.trim());
        brandRepository.flush();
        return BrandResponse.from(brand);
    }

    @Transactional(readOnly = true)
    public BrandResponse get(Long brandId) {
        return BrandResponse.from(require(brandId));
    }

    /** companyId 없으면 전체 */
    @Transactional(readOnly = true)
    public List<BrandResponse> list(Long companyId) {
        List<Brand> brands = companyId == null ? brandRepository.findAllByOrderByIdAsc()
                : brandRepository.findByCompanyId(companyId);
        return brands.stream().map(BrandResponse::from).toList();
    }

    /**
     * @throws ConflictException ACTIVE 판매상품 있음
     */
    public BrandResponse deactivate(Long brandId) {
        Brand brand = require(brandId);
        requireNoActiveSaleProducts(brand, "비활성화");
        brand.deactivate();
        return BrandResponse.from(brand);
    }

    /**
     * @throws ConflictException 계약종료된 브랜드(재활성화 불가) / 소속 회사 비활성
     */
    public BrandResponse activate(Long brandId) {
        Brand brand = require(brandId);
        if (brand.isTerminated()) {
            throw new ConflictException("계약종료된 브랜드 — 재활성화 불가. brandId=" + brandId);
        }
        requireActiveCompany(companyRepository.findById(brand.getCompanyId()).orElseThrow());
        brand.activate();
        return BrandResponse.from(brand);
    }

    /**
     * 계약종료 (불가역).
     *
     * @param confirm 브랜드명 그대로 — 실수 방지 확인
     * @throws InvalidRequestException 확인값 불일치
     * @throws ConflictException       ACTIVE 판매상품 있음 / 이미 계약종료
     */
    public TerminationResponse terminate(Long brandId, String confirm) {
        Brand brand = require(brandId);
        if (!brand.getName().equals(confirm)) {
            throw new InvalidRequestException("계약종료는 되돌릴 수 없습니다. 확인값(confirm)에 브랜드명을 그대로 입력하세요.");
        }
        requireNoActiveSaleProducts(brand, "계약종료");
        brand.terminate();
        return new TerminationResponse(brand.getId(), brand.getName(), brand.getDeletedAt(), TerminationResponse.NOTICE);
    }

    private void requireNoActiveSaleProducts(Brand brand, String action) {
        List<SaleProduct> active = saleProductRepository.findByBrandIdAndStatus(brand.getId(), ActiveStatus.ACTIVE);
        if (!active.isEmpty()) {
            throw new ConflictException("ACTIVE 판매상품이 있어 " + action + "할 수 없습니다. 판매상품을 먼저 비활성화하세요: "
                    + active.stream().map(SaleProduct::getCode).collect(Collectors.joining(", ")));
        }
    }

    private static void requireActiveCompany(Company company) {
        if (!company.isActive()) {
            throw new ConflictException("비활성 회사입니다. 회사를 먼저 활성화하세요. companyId=" + company.getId());
        }
    }

    private Brand require(Long brandId) {
        return brandRepository.findById(brandId)
                .orElseThrow(() -> new NotFoundException("브랜드를 찾을 수 없습니다. brandId=" + brandId));
    }
}
