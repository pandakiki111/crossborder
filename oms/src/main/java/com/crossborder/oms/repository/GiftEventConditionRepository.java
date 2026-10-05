package com.crossborder.oms.repository;

import com.crossborder.common.entity.gift.GiftEventCondition;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface GiftEventConditionRepository extends JpaRepository<GiftEventCondition, Long> {

    List<GiftEventCondition> findByGiftEventIdInOrderByIdAsc(Collection<Long> giftEventIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from GiftEventCondition c where c.giftEventId = :giftEventId")
    void deleteByGiftEventId(Long giftEventId);
}
