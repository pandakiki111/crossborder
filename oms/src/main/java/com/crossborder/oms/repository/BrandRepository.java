package com.crossborder.oms.repository;

import com.crossborder.common.entity.ActiveStatus;
import com.crossborder.common.entity.organization.Brand;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandRepository extends JpaRepository<Brand, Long> {

    List<Brand> findByCompanyId(Long companyId);

    List<Brand> findByCompanyIdAndStatus(Long companyId, ActiveStatus status);

    /** 계약종료되지 않은 브랜드 (ACTIVE·INACTIVE) — 회사 계약종료 엄격 거부 */
    List<Brand> findByCompanyIdAndDeletedAtIsNull(Long companyId);

    List<Brand> findAllByOrderByIdAsc();
}
