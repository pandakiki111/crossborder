package com.crossborder.oms.repository;

import com.crossborder.common.entity.gift.GiftEventActivePeriod;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface GiftEventActivePeriodRepository extends JpaRepository<GiftEventActivePeriod, Long> {

    List<GiftEventActivePeriod> findByGiftEventIdInOrderByActiveFromAsc(Collection<Long> giftEventIds);

    Optional<GiftEventActivePeriod> findByGiftEventIdAndActiveToIsNull(Long giftEventId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from GiftEventActivePeriod p where p.giftEventId = :giftEventId")
    void deleteByGiftEventId(Long giftEventId);
}
