package com.crossborder.oms.service.organization;

import com.crossborder.common.entity.ActiveStatus;
import com.crossborder.common.entity.organization.Brand;
import com.crossborder.common.entity.organization.Company;
import com.crossborder.oms.dto.organization.CompanyRequest;
import com.crossborder.oms.dto.organization.CompanyResponse;
import com.crossborder.oms.dto.organization.TerminationResponse;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.oms.repository.BrandRepository;
import com.crossborder.oms.repository.CompanyRepository;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회사 관리 (ADMIN 전용 — /api/admin/companies).
 * <ul>
 *   <li>비활성화(가역): ACTIVE 브랜드가 있으면 409 + 브랜드 목록 (브랜드부터 정리). 소속 사용자는 막지 않는다</li>
 *   <li>계약종료(불가역): 계약종료되지 않은 브랜드(INACTIVE 포함)가 있으면 409 — 재활성화할 수 없는 회사 아래에
 *       "비활성이지만 계약 유지" 브랜드가 남는 모순을 막는다. 브랜드를 모두 계약종료한 뒤 회사를 계약종료한다</li>
 *   <li>계약종료는 확인값(회사명 그대로)이 필요하고, 계약종료된 회사의 재활성화는 409 (Company.activate 가드)</li>
 * </ul>
 */
@Service
@Transactional
public class CompanyAdminService {

    private final CompanyRepository companyRepository;
    private final BrandRepository brandRepository;

    public CompanyAdminService(CompanyRepository companyRepository, BrandRepository brandRepository) {
        this.companyRepository = companyRepository;
        this.brandRepository = brandRepository;
    }

    public CompanyResponse create(CompanyRequest request) {
        return CompanyResponse.from(companyRepository.save(Company.create(request.name().trim(), request.businessNo())));
    }

    public CompanyResponse update(Long companyId, CompanyRequest request) {
        Company company = require(companyId);
        company.changeInfo(request.name().trim(), request.businessNo());
        return CompanyResponse.from(company);
    }

    @Transactional(readOnly = true)
    public CompanyResponse get(Long companyId) {
        return CompanyResponse.from(require(companyId));
    }

    @Transactional(readOnly = true)
    public List<CompanyResponse> list() {
        return companyRepository.findAllByOrderByIdAsc().stream().map(CompanyResponse::from).toList();
    }

    /**
     * @throws ConflictException ACTIVE 브랜드 있음
     */
    public CompanyResponse deactivate(Long companyId) {
        Company company = require(companyId);
        requireNoActiveBrands(company, "비활성화");
        company.deactivate();
        return CompanyResponse.from(company);
    }

    /**
     * @throws ConflictException 계약종료된 회사 (재활성화 불가)
     */
    public CompanyResponse activate(Long companyId) {
        Company company = require(companyId);
        if (company.isTerminated()) {
            throw new ConflictException("계약종료된 회사 — 재활성화 불가. companyId=" + companyId);
        }
        company.activate();
        return CompanyResponse.from(company);
    }

    /**
     * 계약종료 (불가역).
     *
     * @param confirm 회사명 그대로 — 실수 방지 확인
     * @throws InvalidRequestException 확인값 불일치
     * @throws ConflictException       계약종료되지 않은 브랜드 있음 / 이미 계약종료
     */
    public TerminationResponse terminate(Long companyId, String confirm) {
        Company company = require(companyId);
        if (!company.getName().equals(confirm)) {
            throw new InvalidRequestException("계약종료는 되돌릴 수 없습니다. 확인값(confirm)에 회사명을 그대로 입력하세요.");
        }
        requireAllBrandsTerminated(company);
        company.terminate();
        return new TerminationResponse(company.getId(), company.getName(), company.getDeletedAt(),
                TerminationResponse.NOTICE);
    }

    private void requireNoActiveBrands(Company company, String action) {
        List<Brand> active = brandRepository.findByCompanyIdAndStatus(company.getId(), ActiveStatus.ACTIVE);
        if (!active.isEmpty()) {
            throw new ConflictException("ACTIVE 브랜드가 있어 " + action + "할 수 없습니다. 브랜드를 먼저 비활성화하세요: "
                    + active.stream().map(b -> b.getName() + "(id=" + b.getId() + ")").collect(Collectors.joining(", ")));
        }
    }

    private void requireAllBrandsTerminated(Company company) {
        List<Brand> open = brandRepository.findByCompanyIdAndDeletedAtIsNull(company.getId());
        if (!open.isEmpty()) {
            throw new ConflictException("계약종료되지 않은 브랜드가 있어 회사를 계약종료할 수 없습니다. 브랜드를 먼저 계약종료하세요: "
                    + open.stream().map(b -> b.getName() + "(id=" + b.getId() + ", " + b.getStatus() + ")")
                    .collect(Collectors.joining(", ")));
        }
    }

    private Company require(Long companyId) {
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("회사를 찾을 수 없습니다. companyId=" + companyId));
    }
}
