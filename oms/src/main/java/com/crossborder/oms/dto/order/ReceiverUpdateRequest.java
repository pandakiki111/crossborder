package com.crossborder.oms.dto.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 수취인·배송메모 수정. 길이 제한은 orders 컬럼 기준.
 * 전체 교체 방식: 선택값(전화·우편번호·배송메모)을 비워 보내면 비워진다.
 * 출고지시 이후(SHIPPING·DELIVERED) 주문은 수정 불가 (409).
 */
public record ReceiverUpdateRequest(
        @NotBlank @Size(max = 100) String receiverName,
        @Size(max = 30) String receiverPhone,
        @Size(max = 10) String receiverZipcode,
        @NotBlank @Size(max = 500) String receiverAddress,
        @Size(max = 300) String deliveryMemo
) {
}
