package com.crossborder.common.entity.organization;

public enum UserRole {
    ADMIN,
    WORKER,
    COMPANY_STAFF,
    BRAND_STAFF;

    /**
     * 관리자군 (이메일 필수, TOTP 인증). WORKER는 기기 신뢰 기반.
     */
    public boolean isAdminGroup() {
        return this != WORKER;
    }
}
