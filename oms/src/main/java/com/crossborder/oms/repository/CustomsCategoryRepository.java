package com.crossborder.oms.repository;

import com.crossborder.common.entity.product.CustomsCategory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomsCategoryRepository extends JpaRepository<CustomsCategory, Long> {
}
