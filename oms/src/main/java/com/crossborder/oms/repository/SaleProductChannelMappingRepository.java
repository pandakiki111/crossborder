package com.crossborder.oms.repository;

import com.crossborder.common.entity.product.SaleProductChannelMapping;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 매핑은 이력 테이블이다. 키 하나에 여러 행이 있을 수 있고, 현재 유효 행은 effectiveTo가 null인 최대 1행.
 * optionCode는 SaleProductChannelMapping.normalizeOptionCode로 정규화한 값을 넘겨야 한다 (옵션 없음 = '').
 */
public interface SaleProductChannelMappingRepository extends JpaRepository<SaleProductChannelMapping, Long> {

    /**
     * 대량 조회: 키들의 전체 이력. 옵션코드·기간은 호출 측에서 메모리 매칭. codes는 IN 절 크기 단위로 나눠서 넘긴다.
     * <p>
     * 브랜드 범위는 판매상품 브랜드(sale_products.brand_id)로 보장한다. m.brandId는 복사값이라 인덱스용으로만 함께 건다.
     */
    @Query("""
            select m from SaleProductChannelMapping m
            join SaleProduct sp on sp.id = m.saleProductId
            where m.channelId = :channelId and m.brandId = :brandId and sp.brandId = :brandId
              and m.code in :codes
            """)
    List<SaleProductChannelMapping> findHistoryByCodes(@Param("brandId") Long brandId,
                                                       @Param("channelId") Long channelId,
                                                       @Param("codes") Collection<String> codes);

    List<SaleProductChannelMapping> findByChannelIdAndBrandIdAndCodeAndOptionCodeOrderByEffectiveFromAsc(
            Long channelId, Long brandId, String code, String optionCode);

    /** 키의 최종 마감 시각 (이력이 없으면 empty). 현재 행이 없을 때 재개 시작 시각 산정용 */
    @Query("""
            select max(m.effectiveTo) from SaleProductChannelMapping m
            where m.channelId = :channelId and m.brandId = :brandId
              and m.code = :code and m.optionCode = :optionCode
            """)
    Optional<LocalDateTime> findLastEffectiveTo(@Param("channelId") Long channelId,
                                                @Param("brandId") Long brandId,
                                                @Param("code") String code,
                                                @Param("optionCode") String optionCode);

    /** 키의 현재 유효 행을 잠그고 조회 (등록·재지정 직렬화) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select m from SaleProductChannelMapping m
            where m.channelId = :channelId and m.brandId = :brandId
              and m.code = :code and m.optionCode = :optionCode
              and m.effectiveTo is null
            """)
    Optional<SaleProductChannelMapping> findCurrentForUpdate(@Param("channelId") Long channelId,
                                                             @Param("brandId") Long brandId,
                                                             @Param("code") String code,
                                                             @Param("optionCode") String optionCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from SaleProductChannelMapping m where m.id = :id")
    Optional<SaleProductChannelMapping> findByIdForUpdate(@Param("id") Long id);
}
