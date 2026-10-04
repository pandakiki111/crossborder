package com.crossborder.oms.security.scope;

/**
 * @ScopeCheck가 검사하는 대상. @ScopeId 파라미터가 이 대상의 id다.
 */
public enum ScopeTarget {
    BRAND,
    ORDER,
    /** 출고 회차 — 회차 브랜드 기준 (ADMIN 전체 / COMPANY_STAFF 자기 회사 브랜드 / BRAND_STAFF 자기 브랜드) */
    SHIPMENT
}
