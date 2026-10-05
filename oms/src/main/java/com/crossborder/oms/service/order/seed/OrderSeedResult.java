package com.crossborder.oms.service.order.seed;

/**
 * 시딩 결과. 건수는 주문 단위 (행 단위 아님).
 *
 * @param file          업로드 원본 + 처리결과 열
 * @param successCount  모든 항목이 매핑되어 등록된 주문
 * @param unmappedCount 등록됐지만 매핑안됨 항목이 있는 주문
 */
/**
 * @param giftGrants      이벤트 증정 기록 수 (applyGiftEvents=true일 때)
 * @param giftFailedCount 증정 처리 실패 주문 수 (주문 등록은 성공 — 재평가 대상, 결과 파일 해당 행에 안내)
 */
public record OrderSeedResult(byte[] file, int successCount, int unmappedCount, int skippedCount, int failedCount,
                              int giftGrants, int giftFailedCount) {

    public int totalCount() {
        return successCount + unmappedCount + skippedCount + failedCount;
    }
}
