package com.crossborder.oms.dto.organization;

/**
 * 임시 비밀번호 발급 결과. 평문은 이 응답에서 한 번만 내려가고 서버에는 해시만 남는다.
 */
public record PasswordResetResponse(Long userId, String temporaryPassword, String notice) {

    public static final String NOTICE = "임시 비밀번호는 이 응답에서만 확인할 수 있습니다. 사용자에게 전달 후 변경하도록 안내하세요.";
}
