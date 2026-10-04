package com.crossborder.oms.exception;

/**
 * 대상 없음 또는 조회 스코프 밖 (스코프 밖도 존재를 드러내지 않도록 같은 404)
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
