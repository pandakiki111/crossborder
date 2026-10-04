package com.crossborder.oms.service.order.seed;

import com.crossborder.oms.service.order.seed.OrderSeedSheet.ResultKind;
import java.util.HashMap;
import java.util.Map;

/**
 * 청크 처리 결과의 압축 보관. 3차 패스에서 원본 행 순서대로 처리결과 문자열을 조립한다.
 * <ul>
 *   <li>주문별 1건: 결과 종류 + 텍스트(성공·매핑안됨 = 주문번호, 등록 실패 = 사유, 그 외 null)</li>
 *   <li>행별 사유는 실패 원인 행·매핑안됨 행만 보관 — 성공·스킵·형제 실패 행은 주문별 결과로 조립한다</li>
 * </ul>
 */
final class SeedResultStore {

    record RowResult(ResultKind kind, String message) {
    }

    private final SeedOrderIndex index;
    private final ResultKind[] orderKinds;
    private final String[] orderTexts;
    private final Map<Integer, String> rowDetails = new HashMap<>();

    SeedResultStore(SeedOrderIndex index) {
        this.index = index;
        this.orderKinds = new ResultKind[index.orderCount()];
        this.orderTexts = new String[index.orderCount()];
    }

    void recordOrder(int orderSeq, ResultKind kind, String text) {
        orderKinds[orderSeq] = kind;
        orderTexts[orderSeq] = text;
    }

    void recordRowDetail(int rowIndex, String detail) {
        rowDetails.put(rowIndex, detail);
    }

    /** 처리결과가 없는 행(데이터 없는 행)은 null */
    RowResult resultOf(int rowIndex) {
        int seq = index.orderOf(rowIndex);
        if (seq < 0 || orderKinds[seq] == null) {
            return null;
        }
        ResultKind kind = orderKinds[seq];
        String text = orderTexts[seq];
        String detail = rowDetails.get(rowIndex);
        String message = switch (kind) {
            case SUCCESS -> "성공: " + text;
            case UNMAPPED -> "성공(매핑안됨): " + text + (detail != null ? " - " + detail : "");
            case SKIPPED -> "스킵: " + OrderSeedService.SKIPPED;
            case FAILED -> "실패: " + (detail != null ? detail : text != null ? text : OrderSeedService.SIBLING_FAILED);
        };
        return new RowResult(kind, message);
    }
}
