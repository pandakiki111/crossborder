package com.crossborder.common.entity.order;

public enum ShipmentStatus {
    /** 분리만 된 상태, 출고지시 대기 */
    CREATED,
    INSTRUCTED,
    PICKED,
    PACKED,
    PALLETIZED,
    MASTER_SHIPPED,
    CANCELED
}
