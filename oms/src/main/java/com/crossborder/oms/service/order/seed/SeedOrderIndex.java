package com.crossborder.oms.service.order.seed;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 1차 패스 결과: 행 → 주문 순번, 주문별 행 수. 행 데이터는 보관하지 않는다.
 * <p>
 * 주문 순번은 첫 등장 순서. 같은 (채널코드, 채널주문번호)가 파일 어디에 다시 나와도 같은 순번으로 병합된다.
 * 키 문자열 맵은 빌드 중에만 쓰고 build() 후에는 int 배열만 남는다.
 */
final class SeedOrderIndex {

    /** 행 인덱스 → 주문 순번 (-1 = 데이터 없는 행) */
    private final int[] rowOrder;
    private final int[] orderRowCounts;

    private SeedOrderIndex(int[] rowOrder, int[] orderRowCounts) {
        this.rowOrder = rowOrder;
        this.orderRowCounts = orderRowCounts;
    }

    int orderOf(int rowIndex) {
        return rowIndex < rowOrder.length ? rowOrder[rowIndex] : -1;
    }

    int rowCountOf(int orderSeq) {
        return orderRowCounts[orderSeq];
    }

    int orderCount() {
        return orderRowCounts.length;
    }

    static final class Builder {

        private final Map<String, Integer> seqByKey = new HashMap<>();
        private int[] rowOrder = new int[1024];
        private int[] orderRowCounts = new int[256];
        private int orders;
        private int rows;

        Builder() {
            Arrays.fill(rowOrder, -1);
        }

        void add(int rowIndex, String orderKey) {
            int seq = seqByKey.computeIfAbsent(orderKey, k -> orders++);
            if (seq >= orderRowCounts.length) {
                orderRowCounts = Arrays.copyOf(orderRowCounts, orderRowCounts.length * 2);
            }
            orderRowCounts[seq]++;
            if (rowIndex >= rowOrder.length) {
                int oldLength = rowOrder.length;
                rowOrder = Arrays.copyOf(rowOrder, Math.max(oldLength * 2, rowIndex + 1));
                Arrays.fill(rowOrder, oldLength, rowOrder.length, -1);
            }
            rowOrder[rowIndex] = seq;
            rows++;
        }

        int rowCount() {
            return rows;
        }

        SeedOrderIndex build() {
            return new SeedOrderIndex(rowOrder, Arrays.copyOf(orderRowCounts, orders));
        }
    }
}
