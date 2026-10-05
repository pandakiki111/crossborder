package com.crossborder.oms.service.gift;

import com.crossborder.common.entity.gift.GiftAggregation;
import com.crossborder.common.entity.gift.GiftConditionMode;
import com.crossborder.common.entity.gift.GiftConditionTarget;
import com.crossborder.common.entity.gift.GiftEvent;
import com.crossborder.common.entity.gift.GiftEventCondition;
import com.crossborder.common.entity.gift.GiftQuantityMode;
import com.crossborder.common.entity.gift.GiftTimeBasis;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 이벤트 설정을 운영자 문장으로 옮긴다 (등록 화면의 확인 문구, 응답 preview). 예:
 * "주문시간 기준 10/1 06:00~20:00, 상품 A·B 모두 구매, 결제금액 100,000 이상 주문에 SKU001 1개 — 선착순 100개"
 * <p>
 * 기간 끝·금액 상한은 미포함이다 (문장에도 "미만"). 금액은 주문 통화 그대로라 단위를 붙이지 않는다.
 */
public final class GiftEventPreview {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M/d");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private GiftEventPreview() {
    }

    /** 증정 품목 표시용 (우선순위 순) */
    public record Gift(String sku, Integer limitQty) {
    }

    public static String describe(GiftEvent event, List<GiftEventCondition> conditions, List<Gift> gifts) {
        List<String> parts = new ArrayList<>();
        parts.add((event.getTimeBasis() == GiftTimeBasis.PAID ? "결제시간" : "주문시간") + " 기준 "
                + period(event.getStartsAt(), event.getEndsAt()));
        if (!conditions.isEmpty()) {
            parts.add(conditionText(event.getConditionMode(), conditions));
        }
        String amount = amountText(event.getAmountMin(), event.getAmountMax());
        if (amount != null) {
            parts.add(amount);
        }
        return String.join(", ", parts) + " 주문에 " + giftText(event, gifts);
    }

    static String period(LocalDateTime from, LocalDateTime to) {
        if (from.toLocalDate().equals(to.toLocalDate())) {
            return from.format(DATE) + " " + from.format(TIME) + "~" + to.format(TIME);
        }
        String year = from.getYear() == to.getYear() ? "" : "yyyy/";
        DateTimeFormatter date = DateTimeFormatter.ofPattern(year + "M/d HH:mm");
        return from.format(date) + " ~ " + to.format(date);
    }

    private static String conditionText(GiftConditionMode mode, List<GiftEventCondition> conditions) {
        String targets = conditions.stream().map(GiftEventPreview::target).collect(Collectors.joining("·"));
        if (conditions.size() == 1) {
            return "상품 " + targets + " 구매";
        }
        return "상품 " + targets + (mode == GiftConditionMode.ALL ? " 모두 구매" : " 중 하나 이상 구매");
    }

    private static String target(GiftEventCondition c) {
        if (c.getTargetType() == GiftConditionTarget.SKU) {
            return c.getSku() + " 포함 상품";
        }
        return c.getOptionCode() == null ? c.getSaleProductCode()
                : c.getSaleProductCode() + "(옵션 " + (c.getOptionCode().isEmpty() ? "없음" : c.getOptionCode()) + ")";
    }

    static String amountText(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            return null;
        }
        if (max == null) {
            return "결제금액 " + number(min) + " 이상";
        }
        if (min == null) {
            return "결제금액 " + number(max) + " 미만";
        }
        return "결제금액 " + number(min) + " 이상 " + number(max) + " 미만";
    }

    private static String giftText(GiftEvent event, List<Gift> gifts) {
        String quantity = quantityText(event);
        return switch (event.getGrantType()) {
            case ALWAYS -> gifts.stream().map(g -> g.sku() + " " + quantity).collect(Collectors.joining(", "));
            case SEQUENTIAL -> gifts.stream()
                    .map(g -> g.sku() + " " + quantity + limitText(g))
                    .collect(Collectors.joining(", 소진 시 "));
            case RANDOM -> gifts.stream().map(Gift::sku).collect(Collectors.joining("·")) + " 중 랜덤 1종 " + quantity
                    + randomLimits(gifts);
        };
    }

    private static String quantityText(GiftEvent event) {
        if (event.getQuantityMode() == GiftQuantityMode.FIXED) {
            return event.getFixedQty() + "개";
        }
        return "(조건 상품 " + event.getPerQtyUnit() + "개마다 " + event.getPerQtyGive() + "개, "
                + (event.getAggregation() == GiftAggregation.COMBINED ? "합산" : "상품별") + ")";
    }

    private static String limitText(Gift gift) {
        return gift.limitQty() == null ? "" : " — 선착순 " + gift.limitQty() + "개";
    }

    private static String randomLimits(List<Gift> gifts) {
        String limits = gifts.stream().filter(g -> g.limitQty() != null)
                .map(g -> g.sku() + " 선착순 " + g.limitQty() + "개").collect(Collectors.joining(", "));
        return limits.isEmpty() ? "" : " (" + limits + ")";
    }

    private static String number(BigDecimal value) {
        return new DecimalFormat("#,##0.##").format(value);
    }
}
