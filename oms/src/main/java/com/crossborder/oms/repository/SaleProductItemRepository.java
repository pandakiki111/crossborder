package com.crossborder.oms.repository;

import com.crossborder.common.entity.product.SaleProductItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleProductItemRepository extends JpaRepository<SaleProductItem, Long> {

    /** 판매상품 구성 일괄 조회 (재고 전개용). saleProductIds는 IN 절 크기 단위로 나눠서 넘긴다 */
    List<SaleProductItem> findBySaleProductIdIn(Collection<Long> saleProductIds);

    List<SaleProductItem> findBySaleProductIdOrderByIdAsc(Long saleProductId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SaleProductItem i where i.saleProductId = :saleProductId")
    void deleteBySaleProductId(@Param("saleProductId") Long saleProductId);
}
