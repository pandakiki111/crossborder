package com.crossborder.common.entity.gift;

/**
 * 이벤트 상태 (저장하지 않고 활성 구간·기간에서 파생): 열린 활성 구간 없음 = STOPPED, 기간 끝 지남 = ENDED
 */
public enum GiftEventStatus {
    ACTIVE,
    STOPPED,
    ENDED
}
