package com.crossborder.oms.dto.product;

import java.time.LocalDateTime;

/**
 * 매핑 이력 1행
 *
 * @param optionCode    옵션 없음은 빈 문자열
 * @param effectiveFrom 유효 시작 (포함). 1000-01-01이면 기간 시작 없음 (최초 매핑)
 * @param effectiveTo   유효 끝 (미포함). null이면 현재 유효
 */
public record ChannelMappingResponse(
        Long mappingId,
        Long channelId,
        String channelCode,
        String code,
        String optionCode,
        Long saleProductId,
        String saleProductCode,
        String saleProductName,
        LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo
) {
}
