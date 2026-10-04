package com.crossborder.oms.dto.product;

/**
 * 채널 상품 매핑 검색 조건 (쿼리 파라미터). 모두 선택값.
 *
 * @param code 채널 상품코드 (부분 일치)
 */
public record ChannelMappingSearchCondition(
        Long channelId,
        String code,
        Long saleProductId
) {
}
