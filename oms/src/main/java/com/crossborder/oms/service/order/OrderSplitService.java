package com.crossborder.oms.service.order;

import com.crossborder.oms.dto.order.OrderSplitRequest;
import com.crossborder.oms.dto.order.ShipmentResponse;
import com.crossborder.oms.dto.order.SplitPreviewResponse;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.security.scope.ScopeCheck;
import com.crossborder.oms.security.scope.ScopeId;
import com.crossborder.oms.security.scope.ScopeTarget;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 분리 (출고 회차 생성).
 * <p>
 * 규칙: 분리 대상은 status=ORDERED 항목만 / 한 회차 = 단일 브랜드 / 회차는 CREATED로 생성(지시 전 대기).
 * 매핑안됨(mappingPending) 주문은 분리 불가 — 판매상품·브랜드가 확정되지 않았다.
 */
@Service
public class OrderSplitService {

    /**
     * TODO: 브랜드별로 나눈 뒤 통관 분류(customs_categories.qty_limit) 합산 한도 초과분을 회차로 쪼갠 제안.
     *  판매상품 구성(sale_product_items) 전개 후 분류별 수량 합산
     */
    @ScopeCheck(ScopeTarget.ORDER)
    @Transactional(readOnly = true)
    public SplitPreviewResponse preview(@ScopeId Long orderId, AuthenticatedUser user) {
        throw new UnsupportedOperationException("분리 미리보기 미구현");
    }

    /**
     * TODO: 검증 — 항목별 회차 수량 합 = 미배정 수량, 회차 내 단일 브랜드, CREATED 회차만 있을 때 재분리 허용 여부.
     *  shipment_no = {order_no}-{round_no}, total_amount 산정, 회차 상태 이력 기록
     */
    @ScopeCheck(ScopeTarget.ORDER)
    @Transactional
    public List<ShipmentResponse> split(@ScopeId Long orderId, OrderSplitRequest request, AuthenticatedUser user) {
        throw new UnsupportedOperationException("주문 분리 미구현");
    }
}
