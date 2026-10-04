package com.crossborder.oms.repository;

import com.crossborder.common.entity.order.OrderItem;
import com.crossborder.common.entity.order.OrderItemStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    boolean existsByOrderIdAndBrandId(Long orderId, Long brandId);

    /** 주문들의 전체 항목 (재고 전개용). orderIds는 IN 절 크기 단위로 나눠서 넘긴다 */
    List<OrderItem> findByOrderIdIn(Collection<Long> orderIds);


    /**
     * 채널·브랜드·채널 코드가 일치하는 매핑안됨 항목. 채널 수신 사은품도 channel_product_code가 있으므로 item_type으로 거른다.
     *
     * @param optionCode 정규화된 값 (옵션 없음 = '')
     */
    @Query("""
            select i from OrderItem i, Order o
            where o.id = i.orderId
              and o.salesChannelId = :channelId
              and i.brandId = :brandId
              and i.itemType = com.crossborder.common.entity.order.OrderItemType.SALE_PRODUCT
              and i.saleProductId is null
              and i.channelProductCode = :code
              and i.channelOptionCode = :optionCode
            """)
    List<OrderItem> findUnmapped(@Param("channelId") Long channelId, @Param("brandId") Long brandId,
                                 @Param("code") String code, @Param("optionCode") String optionCode);

    /** 유효(ORDERED) 매핑안됨 항목이 남아 있는 주문. orderIds는 IN 절 크기 단위로 나눠서 넘긴다 */
    @Query("""
            select distinct i.orderId from OrderItem i
            where i.orderId in :orderIds
              and i.itemType = com.crossborder.common.entity.order.OrderItemType.SALE_PRODUCT
              and i.saleProductId is null
              and i.status = com.crossborder.common.entity.order.OrderItemStatus.ORDERED
            """)
    List<Long> findOrderIdsWithUnmapped(@Param("orderIds") Collection<Long> orderIds);

    /** 취소된 항목이 있는 주문. orderIds는 IN 절 크기 단위로 나눠서 넘긴다 */
    @Query("select distinct i.orderId from OrderItem i where i.orderId in :orderIds and i.status = :status")
    List<Long> findOrderIdsWithStatus(@Param("orderIds") Collection<Long> orderIds,
                                      @Param("status") OrderItemStatus status);
}
