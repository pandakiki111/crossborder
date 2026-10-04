package com.crossborder.oms.repository;

import com.crossborder.common.entity.order.Order;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** uk_orders_channel_order 기준 (수집·엑셀 시딩 멱등성 키) */
    boolean existsBySalesChannelIdAndChannelOrderNo(Long salesChannelId, String channelOrderNo);

    boolean existsByOrderNo(String orderNo);

    /** 대량 등록 사전 중복 체크. channelOrderNos는 호출 측이 IN 절 크기 단위로 나눠서 넘긴다 */
    @Query("select o.channelOrderNo from Order o where o.salesChannelId = :channelId and o.channelOrderNo in :channelOrderNos")
    List<String> findExistingChannelOrderNos(@Param("channelId") Long channelId,
                                             @Param("channelOrderNos") Collection<String> channelOrderNos);
}
