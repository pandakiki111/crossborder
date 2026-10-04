package com.crossborder.oms.service.order;

import com.crossborder.common.entity.order.Order;
import com.crossborder.common.entity.order.OrderItem;
import java.util.List;
import java.util.Map;

/**
 * 저장 전 주문. 엔티티 팩토리로 만들어 도메인 규칙 검증은 마친 상태이고, OrderBatchWriter가 값을 꺼내 쓴다.
 * 항목의 orderId는 아직 null (주문 INSERT 후 writer가 채운 id로 쓴다).
 *
 * @param allocation 제품별 할당 수량 (전개 결과). 매핑안됨 주문은 null — 할당하지 않고 매핑 완료 시 할당한다
 */
record OrderDraft(Order order, List<OrderItem> items, Map<Long, Integer> allocation) {

    boolean allocates() {
        return allocation != null;
    }
}
