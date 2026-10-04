package com.crossborder.oms.exception;

/**
 * 현재 상태와 충돌하는 요청 (409). 예: 이미 유효한 매핑이 있는 키에 신규 등록
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
