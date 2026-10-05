package com.crossborder.oms.repository;

import com.crossborder.common.entity.gift.GiftEventItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface GiftEventItemRepository extends JpaRepository<GiftEventItem, Long> {

    List<GiftEventItem> findByGiftEventIdInOrderByPriorityAscIdAsc(Collection<Long> giftEventIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from GiftEventItem i where i.giftEventId = :giftEventId")
    void deleteByGiftEventId(Long giftEventId);
}
