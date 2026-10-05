package com.crossborder.oms.repository;

import com.crossborder.common.entity.organization.Brand;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandRepository extends JpaRepository<Brand, Long> {

    List<Brand> findByCompanyId(Long companyId);
}
