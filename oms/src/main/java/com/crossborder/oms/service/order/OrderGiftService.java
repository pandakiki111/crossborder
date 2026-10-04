package com.crossborder.oms.service.order;

import com.crossborder.oms.dto.order.GiftAddRequest;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.security.scope.ScopeCheck;
import com.crossborder.oms.security.scope.ScopeId;
import com.crossborder.oms.security.scope.ScopeTarget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 건별 사은품 (order_items GIFT_PRODUCT). 판매상품 구성 고정 사은품(sale_product_items.is_gift)과는 별개.
 */
@Service
public class OrderGiftService {

    /**
     * TODO: OrderItem.ofGift. 제품 브랜드가 주문 회사 소속인지 검증, 재고 할당 정책과 함께 처리
     *
     * @return 생성된 주문 항목 id
     */
    @ScopeCheck(ScopeTarget.ORDER)
    @Transactional
    public Long addGift(@ScopeId Long orderId, GiftAddRequest request, AuthenticatedUser user) {
        throw new UnsupportedOperationException("사은품 추가 미구현");
    }

    /**
     * TODO: 사은품은 수량 부분취소 없이 전체 취소만 (OrderItem.cancel). INSTRUCTED 이후 회차에 물린 항목은 불가
     */
    @ScopeCheck(ScopeTarget.ORDER)
    @Transactional
    public void removeGift(@ScopeId Long orderId, Long orderItemId, AuthenticatedUser user) {
        throw new UnsupportedOperationException("사은품 삭제 미구현");
    }
}
