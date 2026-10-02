package com.crossborder.auth.exception;

import org.springframework.http.HttpStatus;

/**
 * 로그인 거부. 응답 상태와 메시지를 함께 담는다.
 */
public class LoginFailedException extends RuntimeException {

    private final HttpStatus status;

    private LoginFailedException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * 사용자 없음·비활성·비밀번호 불일치. 계정 존재 여부가 드러나지 않도록 사유와 무관하게 같은 응답을 쓴다.
     */
    public static LoginFailedException badCredentials() {
        return new LoginFailedException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다");
    }

    /**
     * 정책 안내라서 메시지를 구분한다. 비밀번호 검증을 통과한 뒤에만 던지므로 계정 존재를 노출하지 않는다.
     */
    public static LoginFailedException workerDeviceRequired() {
        return new LoginFailedException(HttpStatus.FORBIDDEN, "작업자 계정은 등록된 기기에서만 로그인할 수 있습니다");
    }

    public HttpStatus getStatus() {
        return status;
    }
}
