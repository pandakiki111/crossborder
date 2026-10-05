package com.crossborder.oms.dto.product;

import com.crossborder.common.entity.ActiveStatus;
import java.util.List;

/**
 * @param warnings 등록·구성 변경·리뉴얼 시 사전 경고 (통관 분류 한도 단독 초과, 리뉴얼로 조건이 끊기는 이벤트, 비활성화 시 남은 현재 매핑). 조회는 빈 목록
 */
public record SaleProductResponse(Long id, Long brandId, String code, String name, ActiveStatus status,
                                  List<Item> items, List<String> warnings) {

    public record Item(Long productId, String sku, int quantity, boolean gift) {
    }
}
