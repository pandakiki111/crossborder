package com.crossborder.oms.dto.order;

import java.util.List;

/**
 * 주문 다운로드 POST 본문 (주문번호 복수 붙여넣기처럼 조건이 길 때).
 *
 * @param columns 열 키 배열 (OrderDownloadColumn 이름, 순서 유지). 비면 전체
 */
public record OrderDownloadRequest(OrderSearchCondition condition, List<String> columns) {
}
