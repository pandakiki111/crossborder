package com.crossborder.infra.jwt;

/**
 * 토큰 검증 실패. jjwt 예외는 이 타입으로 감싸서 infra 밖으로 새지 않게 한다.
 * <p>
 * 클라이언트가 재발급(EXPIRED)과 재로그인(INVALID)을 구분할 수 있도록 사유를 담는다.
 */
public class InvalidTokenException extends RuntimeException {

    public enum Reason {
        EXPIRED,
        INVALID
    }

    private final Reason reason;

    public InvalidTokenException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
