package com.crossborder.oms.dto.gift;

import com.crossborder.common.entity.gift.GiftAggregation;
import com.crossborder.common.entity.gift.GiftConditionMode;
import com.crossborder.common.entity.gift.GiftConditionTarget;
import com.crossborder.common.entity.gift.GiftGrantType;
import com.crossborder.common.entity.gift.GiftQuantityMode;
import com.crossborder.common.entity.gift.GiftTimeBasis;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 사은품 이벤트 등록·수정. 수정은 정의 전체 교체 (brandId는 바꿀 수 없다).
 *
 * @param amountMin  결제금액 하한 (포함, null = 없음)
 * @param amountMax  결제금액 상한 (미포함, null = 없음)
 * @param conditions 상품 조건. 비면 상품 조건 없음 (금액만, FIXED만 가능)
 * @param items      증정 품목 (1개 이상)
 */
public record GiftEventRequest(
        @NotNull Long brandId,
        @NotNull String name,
        @NotNull GiftTimeBasis timeBasis,
        @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime endsAt,
        BigDecimal amountMin,
        BigDecimal amountMax,
        @NotNull GiftConditionMode conditionMode,
        @NotNull GiftGrantType grantType,
        @NotNull GiftQuantityMode quantityMode,
        Integer fixedQty,
        Integer perQtyUnit,
        Integer perQtyGive,
        GiftAggregation aggregation,
        @Valid List<Condition> conditions,
        @Valid @NotEmpty List<Item> items
) {

    /**
     * @param optionCode SALE_PRODUCT: 채널 옵션코드, null = 옵션 무관
     */
    public record Condition(@NotNull GiftConditionTarget targetType, String saleProductCode, String optionCode,
                            String sku) {
    }

    /**
     * @param priority SEQUENTIAL 순서 (작을수록 먼저). 비우면 목록 순서
     * @param limitQty SEQUENTIAL·RANDOM 한도 (null = 무제한). ALWAYS는 비운다
     */
    public record Item(@NotNull Long productId, Integer priority, Integer limitQty) {
    }
}
