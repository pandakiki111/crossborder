package com.crossborder.oms.dto.organization;

import java.time.LocalDateTime;

/**
 * 계약종료(불가역) 결과.
 *
 * @param notice 재활성화 불가 안내
 */
public record TerminationResponse(Long id, String name, LocalDateTime terminatedAt, String notice) {

    public static final String NOTICE = "계약종료 처리되었습니다. 재활성화할 수 없습니다 (조회·기존 주문 처리만 가능).";
}
