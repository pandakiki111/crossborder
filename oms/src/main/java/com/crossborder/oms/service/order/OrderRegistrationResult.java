package com.crossborder.oms.service.order;

/**
 * 주문 1건의 등록 결과. registerAll은 입력 순서대로 돌려준다.
 *
 * @param orderNo        REGISTERED일 때 내부 주문번호
 * @param mappingPending REGISTERED이고 매핑안됨 항목이 있음
 * @param message        DUPLICATE·FAILED 사유
 */
public record OrderRegistrationResult(Status status, String orderNo, boolean mappingPending, String message) {

    public enum Status {
        REGISTERED,
        /** 같은 (채널, 채널주문번호)가 이미 있거나 같은 요청 안에서 중복 — 호출 측은 스킵 처리 */
        DUPLICATE,
        FAILED
    }

    static OrderRegistrationResult registered(String orderNo, boolean mappingPending) {
        return new OrderRegistrationResult(Status.REGISTERED, orderNo, mappingPending, null);
    }

    static OrderRegistrationResult duplicate() {
        return new OrderRegistrationResult(Status.DUPLICATE, null, false, "이미 등록된 주문");
    }

    static OrderRegistrationResult failed(String message) {
        return new OrderRegistrationResult(Status.FAILED, null, false, message);
    }
}
