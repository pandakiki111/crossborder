package com.crossborder.oms.repository;

import com.crossborder.common.entity.order.OrderStatus;
import com.crossborder.common.entity.order.ShipmentStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 검증·정규화를 마친 주문 검색 조건 (OrderQueryService가 OrderSearchCondition에서 만든다).
 * 리스트는 비어 있으면 조건 없음, null 필드도 조건 없음.
 *
 * @param orderedFrom 포함
 * @param orderedTo   미포함 (마지막 날 다음날 0시)
 * @param brandId     브랜드 선택 (BRAND_STAFF는 이미 제거됨 — 스코프가 같은 조건을 건다)
 * @param sku         SKU 조건. null이면 조건 없음
 */
public record OrderSearchCriteria(
        LocalDateTime orderedFrom,
        LocalDateTime orderedTo,
        List<OrderStatus> statuses,
        Boolean mappingPending,
        Long salesChannelId,
        Long brandId,
        List<ShipmentStatus> shipmentStatuses,
        boolean unsplit,
        String orderNo,
        List<String> channelOrderNos,
        String channelOrderNoContains,
        SkuFilter sku
) {

    /**
     * SKU를 해석한 결과. 판매상품(구성에 그 제품 포함) 항목 또는 건별 사은품(그 제품) 항목이 있는 주문.
     *
     * @param dense 해석된 항목 수가 임계를 넘음 → 주문에서 출발하는 프로브 (아니면 항목에서 출발하는 IN 세미조인)
     */
    public record SkuFilter(List<Long> saleProductIds, List<Long> productIds, boolean dense) {

        /** 일치하는 제품이 없음 → 결과 0건 (쿼리 생략) */
        public boolean matchesNothing() {
            return productIds.isEmpty();
        }
    }
}
