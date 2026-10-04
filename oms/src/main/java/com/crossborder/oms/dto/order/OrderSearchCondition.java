package com.crossborder.oms.dto.order;

import com.crossborder.common.entity.order.OrderStatus;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 주문 목록 검색 조건 (쿼리 파라미터). 모두 선택값.
 *
 * @param mappingPending true = 매핑안됨 주문만, false = 매핑완료 주문만 (상태 필터와 함께 쓸 수 있음)
 * @param orderedFrom 주문일 시작 (포함, yyyy-MM-dd)
 * @param orderedTo   주문일 끝 (포함, yyyy-MM-dd)
 */
public record OrderSearchCondition(
        OrderStatus status,
        Boolean mappingPending,
        Long salesChannelId,
        String orderNo,
        String channelOrderNo,
        String receiverName,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate orderedFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate orderedTo
) {
}
