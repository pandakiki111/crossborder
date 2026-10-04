package com.crossborder.infra.lock;

import java.util.Collection;
import java.util.List;

/**
 * 대기 시간 안에 락을 얻지 못함. 호출 측은 해당 작업을 실패로 처리한다 (재시도는 호출 측 판단).
 */
public class LockAcquisitionException extends RuntimeException {

    private final List<String> keys;

    public LockAcquisitionException(Collection<String> keys, String message) {
        super(message);
        this.keys = List.copyOf(keys);
    }

    public LockAcquisitionException(Collection<String> keys, String message, Throwable cause) {
        super(message, cause);
        this.keys = List.copyOf(keys);
    }

    public List<String> getKeys() {
        return keys;
    }
}
