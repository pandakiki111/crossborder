package com.crossborder.oms.dto;

import java.util.List;

/**
 * 건수 상한 페이지 응답. 대용량 목록은 정확한 총건수를 세지 않는다 (README "대용량 조회 검증").
 *
 * @param page          0부터 시작
 * @param totalElements 건수. totalCapped면 상한값 (화면은 "10,000+")
 * @param totalCapped   실제 건수가 상한을 넘음
 */
public record CappedPageResponse<T>(List<T> content, int page, int size, long totalElements, boolean totalCapped) {
}
