package com.crossborder.oms.service.order.seed;

/**
 * 파일 자체를 처리할 수 없음 (xlsx 아님, 헤더 누락 등). 행 단위 오류는 결과 파일에 기록하고 이 예외를 쓰지 않는다.
 */
public class InvalidSeedFileException extends RuntimeException {

    public InvalidSeedFileException(String message) {
        super(message);
    }

    public InvalidSeedFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
