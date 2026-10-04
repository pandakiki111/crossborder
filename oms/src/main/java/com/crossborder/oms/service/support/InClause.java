package com.crossborder.oms.service.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 대량 IN 조회 분할. IN 목록이 너무 길면 파싱·플랜 비용이 커지고 패킷 한도에 걸릴 수 있다.
 */
public final class InClause {

    public static final int MAX_SIZE = 1000;

    private InClause() {
    }

    public static <T> List<List<T>> partition(Collection<T> values) {
        return partition(new ArrayList<>(values), MAX_SIZE);
    }

    public static <T> List<List<T>> partition(List<T> values, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < values.size(); i += size) {
            chunks.add(values.subList(i, Math.min(i + size, values.size())));
        }
        return chunks;
    }
}
