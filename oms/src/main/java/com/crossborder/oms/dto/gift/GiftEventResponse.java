package com.crossborder.oms.dto.gift;

import com.crossborder.common.entity.gift.GiftAggregation;
import com.crossborder.common.entity.gift.GiftConditionMode;
import com.crossborder.common.entity.gift.GiftConditionTarget;
import com.crossborder.common.entity.gift.GiftEventStatus;
import com.crossborder.common.entity.gift.GiftGrantType;
import com.crossborder.common.entity.gift.GiftQuantityMode;
import com.crossborder.common.entity.gift.GiftTimeBasis;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 사은품 이벤트. status는 파생값, preview는 설정을 운영자 문장으로 옮긴 것.
 *
 * @param code       이벤트 코드 (GEVT-XXXXXXXX, 서버 생성·불변)
 * @param grantCount 지급 기록 수 (1 이상이면 수정·삭제 불가)
 */
public record GiftEventResponse(
        Long id, String code, Long brandId, String name, GiftTimeBasis timeBasis, LocalDateTime startsAt, LocalDateTime endsAt,
        BigDecimal amountMin, BigDecimal amountMax, GiftConditionMode conditionMode, GiftGrantType grantType,
        GiftQuantityMode quantityMode, Integer fixedQty, Integer perQtyUnit, Integer perQtyGive,
        GiftAggregation aggregation, GiftEventStatus status, long grantCount, List<Condition> conditions,
        List<Item> items, List<Period> activePeriods, String preview) {

    public record Condition(GiftConditionTarget targetType, String saleProductCode, String optionCode, String sku) {
    }

    /**
     * @param remaining 남은 한도 (null = 무제한)
     */
    public record Item(Long id, Long productId, String sku, int priority, Integer limitQty, int grantedQty,
                       Integer remaining) {
    }

    /** [activeFrom, activeTo), activeTo null = 현재 활성 */
    public record Period(LocalDateTime activeFrom, LocalDateTime activeTo) {
    }
}
