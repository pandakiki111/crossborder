package com.crossborder.oms.repository;

import com.crossborder.common.entity.product.Product;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /** 대량 조회 (사은품 SKU → 제품). 업로드 브랜드의 제품만 본다. skus는 IN 절 크기 단위로 나눠서 넘긴다 */
    List<Product> findByBrandIdAndSkuIn(Long brandId, Collection<String> skus);
}
