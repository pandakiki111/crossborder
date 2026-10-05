package com.crossborder.oms.dto.product;

/**
 * @param previousSaleProductId 비활성화된 구 판매상품
 * @param remappedMappingCount  새 판매상품으로 재지정된 현재 채널 매핑 수
 */
public record SaleProductRenewResponse(Long previousSaleProductId, SaleProductResponse saleProduct,
                                       int remappedMappingCount) {
}
