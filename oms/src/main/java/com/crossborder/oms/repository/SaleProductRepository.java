package com.crossborder.oms.repository;

import com.crossborder.common.entity.ActiveStatus;
import com.crossborder.common.entity.product.SaleProduct;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleProductRepository extends JpaRepository<SaleProduct, Long> {

    /** 이벤트 조건 검증 (판매상품코드 → 판매상품, 브랜드 안에서) */
    List<SaleProduct> findByBrandIdAndCodeIn(Long brandId, Collection<String> codes);

    List<SaleProduct> findByBrandIdAndStatus(Long brandId, ActiveStatus status);

    List<SaleProduct> findByBrandIdInOrderByIdAsc(Collection<Long> brandIds);

    boolean existsByBrandIdAndCode(Long brandId, String code);

    /** 이 제품을 구성에 포함한 판매상품 중 해당 상태 (제품 비활성화 연쇄 거부) */
    @Query("""
            select distinct sp from SaleProduct sp, SaleProductItem spi
            where spi.saleProductId = sp.id and spi.productId = :productId and sp.status = :status
            """)
    List<SaleProduct> findContainingProduct(@Param("productId") Long productId, @Param("status") ActiveStatus status);
}
