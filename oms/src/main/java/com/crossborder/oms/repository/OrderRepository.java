package com.crossborder.oms.repository;

import com.crossborder.common.entity.order.Order;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** uk_orders_channel_order 기준 (수집·엑셀 시딩 멱등성 키) */
    boolean existsBySalesChannelIdAndChannelOrderNo(Long salesChannelId, String channelOrderNo);

    boolean existsByOrderNo(String orderNo);

    /**
     * 주문 행 잠금. 주문 단위 변경(취소 등)을 직렬화한다 — 판정과 처리 사이에 다른 변경이 끼지 않게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    /** 대량 등록 사전 중복 체크. channelOrderNos는 호출 측이 IN 절 크기 단위로 나눠서 넘긴다 */
    @Query("select o.channelOrderNo from Order o where o.salesChannelId = :channelId and o.channelOrderNo in :channelOrderNos")
    List<String> findExistingChannelOrderNos(@Param("channelId") Long channelId,
                                             @Param("channelOrderNos") Collection<String> channelOrderNos);

    /** 주문번호 → id (등록 직후 후속 처리용). [id, orderNo] */
    @Query("select o.id, o.orderNo from Order o where o.orderNo in :orderNos")
    List<Object[]> findIdsByOrderNoIn(@Param("orderNos") Collection<String> orderNos);
}
