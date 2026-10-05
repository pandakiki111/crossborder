package com.crossborder.oms.dto.order;

/**
 * 다운로드 전 건수 (화면의 기간 경고·소요 안내용).
 *
 * @param orderCount    조건에 맞는 주문 수. exceedsLimit이면 상한값
 * @param exceedsLimit  상한을 넘어 이 조건으로는 다운로드할 수 없음 (조건을 좁혀 나눠 받아야 함)
 * @param maxOrders     주문 수 상한 (설정값)
 */
public record OrderDownloadEstimate(long orderCount, boolean exceedsLimit, int maxOrders) {
}
