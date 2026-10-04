package com.crossborder.oms.service.order;

/**
 * 주문 등록 거부. 호출 측(시딩·수집)이 사유를 결과로 기록한다.
 */
public class OrderRegistrationException extends RuntimeException {

    public OrderRegistrationException(String message) {
        super(message);
    }
}
