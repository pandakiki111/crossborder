package com.crossborder.common.entity.product;

/**
 * 물리재고 변동 사유
 */
public enum MovementType {
    /** 입고 (+) */
    INBOUND,
    /** 출고 (-) */
    OUTBOUND,
    /** 취소 복원 (+) */
    CANCEL_RESTORE,
    /** 실사·파손·폐기 등 수동 조정 (+/-) */
    ADJUST;

    public boolean isValidQuantity(int quantity) {
        return switch (this) {
            case INBOUND, CANCEL_RESTORE -> quantity > 0;
            case OUTBOUND -> quantity < 0;
            case ADJUST -> quantity != 0;
        };
    }
}
