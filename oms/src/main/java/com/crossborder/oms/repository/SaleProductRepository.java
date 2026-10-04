package com.crossborder.oms.repository;

import com.crossborder.common.entity.product.SaleProduct;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleProductRepository extends JpaRepository<SaleProduct, Long> {
}
