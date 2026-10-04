package com.crossborder.oms.exception;

/**
 * 요청 값이 업무 규칙상 올바르지 않음 (400). Bean Validation으로 잡을 수 없는 DB 조회 기반 검증용.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
