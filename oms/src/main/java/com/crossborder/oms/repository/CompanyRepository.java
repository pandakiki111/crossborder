package com.crossborder.oms.repository;

import com.crossborder.common.entity.organization.Company;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    List<Company> findAllByOrderByIdAsc();
}
