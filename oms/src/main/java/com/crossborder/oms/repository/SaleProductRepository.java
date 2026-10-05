package com.crossborder.oms.repository;

import com.crossborder.common.entity.product.SaleProduct;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleProductRepository extends JpaRepository<SaleProduct, Long> {

    /** 이벤트 조건 검증 (판매상품코드 → 판매상품, 브랜드 안에서) */
    List<SaleProduct> findByBrandIdAndCodeIn(Long brandId, Collection<String> codes);
}
