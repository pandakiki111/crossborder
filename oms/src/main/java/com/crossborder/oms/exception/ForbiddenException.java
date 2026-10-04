package com.crossborder.oms.exception;

/**
 * 대상은 있지만 인증 사용자의 소속 범위 밖 (403)
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
