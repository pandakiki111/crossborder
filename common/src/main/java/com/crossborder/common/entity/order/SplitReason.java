package com.crossborder.common.entity.order;

public enum SplitReason {
    CUSTOMS_LIMIT,
    /**
     * 현재 미사용. "품목당 24개" 규정의 품목 판별이 데이터로 불가해 자동 분할에서 뺐다 (개수 초과는 경고만).
     * 품목 단위 한도 분할을 도입할 때 재사용한다 — 값은 이력 호환을 위해 유지.
     */
    QUANTITY_LIMIT,
    STOCK_SHORTAGE,
    BRAND_SPLIT,
    MANUAL
}
