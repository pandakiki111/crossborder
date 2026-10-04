package com.crossborder.oms.service.order;

import com.crossborder.common.entity.order.Order;
import com.crossborder.common.entity.order.OrderItem;
import java.util.List;

/**
 * 저장 전 주문. 엔티티 팩토리로 만들어 도메인 규칙 검증은 마친 상태이고, OrderBatchWriter가 값을 꺼내 쓴다.
 * 항목의 orderId는 아직 null (주문 INSERT 후 writer가 채운 id로 쓴다).
 */
record OrderDraft(Order order, List<OrderItem> items) {
}
