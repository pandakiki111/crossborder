package com.crossborder.common.entity.gift;

/**
 * 이벤트 판정 기준 시각. PAID인데 결제 시각이 없으면 주문 시각으로 판정한다
 */
public enum GiftTimeBasis {
    ORDERED,
    PAID
}
