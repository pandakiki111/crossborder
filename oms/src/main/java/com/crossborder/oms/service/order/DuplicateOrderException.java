package com.crossborder.oms.service.order;

/**
 * 같은 (채널, 채널주문번호) 주문이 이미 있음. 실패가 아니라 스킵 대상이라 별도 타입으로 구분한다.
 */
public class DuplicateOrderException extends OrderRegistrationException {

    public DuplicateOrderException(Long salesChannelId, String channelOrderNo) {
        super("이미 등록된 주문입니다. salesChannelId=" + salesChannelId + ", channelOrderNo=" + channelOrderNo);
    }
}
