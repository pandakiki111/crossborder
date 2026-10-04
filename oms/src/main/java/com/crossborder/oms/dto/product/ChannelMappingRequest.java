package com.crossborder.oms.dto.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 채널 상품 매핑 등록·재지정. 재지정 시 channelId는 바꿀 수 없다 (채널이 다르면 새 매핑).
 * 판매상품만 바꾸면 현재 행 마감 + 신규 행(처리 시각부터 유효), 코드·옵션을 바꾸면 기존 키 마감 + 새 키 등록.
 *
 * @param optionCode 옵션 없으면 비움 ('' 로 정규화)
 */
public record ChannelMappingRequest(
        @NotNull Long channelId,
        @NotBlank @Size(max = 100) String code,
        @Size(max = 30) String optionCode,
        @NotNull Long saleProductId
) {
}
