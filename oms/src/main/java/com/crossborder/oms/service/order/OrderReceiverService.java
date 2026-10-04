package com.crossborder.oms.service.order;

import com.crossborder.common.entity.order.Order;
import com.crossborder.oms.dto.order.ReceiverUpdateRequest;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.oms.repository.OrderRepository;
import com.crossborder.oms.security.scope.ScopeCheck;
import com.crossborder.oms.security.scope.ScopeId;
import com.crossborder.oms.security.scope.ScopeTarget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 수취인 정보 수정 (이름·전화번호·우편번호·주소·배송메모).
 */
@Service
public class OrderReceiverService {

    private final OrderRepository orderRepository;

    public OrderReceiverService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * 출고지시 이후(SHIPPING·DELIVERED)는 Order.changeReceiver가 거부한다 (409).
     */
    @ScopeCheck(ScopeTarget.ORDER)
    @Transactional
    public void changeReceiver(@ScopeId Long orderId, ReceiverUpdateRequest request) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다. orderId=" + orderId));
        order.changeReceiver(request.receiverName().trim(), trimToNull(request.receiverPhone()),
                trimToNull(request.receiverZipcode()), request.receiverAddress().trim(),
                trimToNull(request.deliveryMemo()));
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
