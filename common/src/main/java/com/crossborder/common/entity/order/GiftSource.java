package com.crossborder.common.entity.order;

/**
 * 사은품(GIFT_PRODUCT) 항목의 출처. 증정이 잘못됐을 때 어느 경로로 들어왔는지 추적하는 기준.
 */
public enum GiftSource {
    /** 채널 수신·엑셀 시딩 사은품 행 */
    COLLECTED,
    /** 이벤트 자동 증정 (gift_event_id 필수) */
    EVENT,
    /** 운영자 수동 증정 */
    MANUAL
}
