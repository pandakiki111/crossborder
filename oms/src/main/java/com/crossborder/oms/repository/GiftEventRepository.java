package com.crossborder.oms.repository;

import com.crossborder.common.entity.gift.GiftEvent;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GiftEventRepository extends JpaRepository<GiftEvent, Long> {

    /** 판정 후보: 브랜드들의 이벤트 중 기간이 [from, to] 구간과 겹치는 것 */
    @Query("""
            select e from GiftEvent e
            where e.brandId in :brandIds and e.startsAt <= :to and e.endsAt > :from
            """)
    List<GiftEvent> findOverlapping(@Param("brandIds") Collection<Long> brandIds, @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);

    List<GiftEvent> findByBrandIdInOrderByIdDesc(Collection<Long> brandIds);

    List<GiftEvent> findAllByOrderByIdDesc();
}
