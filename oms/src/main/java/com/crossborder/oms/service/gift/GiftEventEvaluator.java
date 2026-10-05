package com.crossborder.oms.service.gift;

import com.crossborder.common.entity.gift.GiftAggregation;
import com.crossborder.common.entity.gift.GiftConditionMode;
import com.crossborder.common.entity.gift.GiftConditionTarget;
import com.crossborder.common.entity.gift.GiftEvent;
import com.crossborder.common.entity.gift.GiftEventActivePeriod;
import com.crossborder.common.entity.gift.GiftEventCondition;
import com.crossborder.common.entity.gift.GiftTimeBasis;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 사은품 이벤트 판정 — 조건은 데이터 행(GiftEventCondition), 판정은 이 클래스 하나 (조건 조합마다 분기를 늘리지 않는다).
 * I/O 없는 순수 로직이라 입력만으로 결과가 정해진다 (랜덤은 주입).
 * <ol>
 *   <li>기준 시각 t: time_basis에 따라 주문 시각 또는 결제 시각(없으면 주문 시각). 처리(수집) 시각은 쓰지 않는다</li>
 *   <li>대상: 주문에 이벤트 브랜드 구매 항목이 있고, t ∈ [startsAt, endsAt) 이며 t를 포함하는 활성 구간이 있음
 *       (지급 이력 확인은 실행기 몫)</li>
 *   <li>조건: 이벤트 브랜드 항목만 본다. SALE_PRODUCT = 판매상품코드(+옵션) 일치, SKU = 구성에 그 제품. ALL/ANY</li>
 *   <li>금액: 결제금액 ∈ [amountMin, amountMax). 결제금액이 없으면 금액 조건이 있는 이벤트는 불충족</li>
 *   <li>수량 N: FIXED = fixedQty / PER_QUANTITY = 조건 상품 구매수량의 몫 × give (COMBINED 합산 / PER_PRODUCT 조건별)</li>
 *   <li>품목 선택: {@link #select}</li>
 * </ol>
 */
public final class GiftEventEvaluator {

    private GiftEventEvaluator() {
    }

    /**
     * 판정 입력의 구매 항목 (유효·매핑 완료 SALE_PRODUCT 항목). 전개는 SKU 조건 판정에만 쓴다.
     *
     * @param optionCode 채널 옵션코드 (옵션 없음 = '')
     * @param skuUnits   판매상품 구성: SKU → 판매상품 1개당 제품 수량
     */
    public record PurchasedItem(Long brandId, String saleProductCode, String optionCode, int quantity,
                                Map<String, Integer> skuUnits) {
    }

    /**
     * @param brandIds 유효 구매 항목의 브랜드 (매핑안됨 항목 포함). 이벤트는 자기 브랜드 항목이 있는 주문만 본다
     * @param items    조건 매칭용 유효·매핑완료 구매 항목 (매핑안됨 항목은 판매상품이 없어 빠진다)
     */
    public record OrderFacts(Long orderId, LocalDateTime orderedAt, LocalDateTime paidAt, BigDecimal paidAmount,
                             Set<Long> brandIds, List<PurchasedItem> items) {
    }

    /**
     * 증정 후보 품목.
     *
     * @param remaining 남은 한도 (limit - granted). null = 무제한
     */
    public record Candidate(Long eventItemId, Long productId, int priority, Integer remaining) {

        boolean canCover(int quantity) {
            return remaining == null || remaining >= quantity;
        }
    }

    public record Rule(GiftEvent event, List<GiftEventCondition> conditions, List<GiftEventActivePeriod> periods,
                       List<Candidate> items) {
    }

    /** 증정 결정: 이벤트 품목 1종 × 수량 */
    public record Decision(Long eventId, Long eventItemId, Long productId, int quantity) {
    }

    public static LocalDateTime basisTime(GiftEvent event, OrderFacts order) {
        if (event.getTimeBasis() == GiftTimeBasis.PAID && order.paidAt() != null) {
            return order.paidAt();
        }
        return order.orderedAt();
    }

    /** 기간 [startsAt, endsAt)과 활성 구간에 t가 들어가는지 */
    public static boolean inEffect(Rule rule, OrderFacts order) {
        LocalDateTime t = basisTime(rule.event(), order);
        GiftEvent event = rule.event();
        return !t.isBefore(event.getStartsAt()) && t.isBefore(event.getEndsAt())
                && rule.periods().stream().anyMatch(period -> period.contains(t));
    }

    /**
     * 기간·조건·금액을 모두 충족하면 증정 수량 N, 아니면 0. 품목 한도는 보지 않는다 ({@link #select}).
     */
    public static int giftQuantity(Rule rule, OrderFacts order) {
        GiftEvent event = rule.event();
        if (!order.brandIds().contains(event.getBrandId()) || !inEffect(rule, order)
                || !amountMatches(event, order.paidAmount())) {
            return 0;
        }
        List<PurchasedItem> items = order.items().stream()
                .filter(item -> Objects.equals(item.brandId(), event.getBrandId()))
                .toList();
        List<GiftEventCondition> conditions = rule.conditions();
        int[] matched = new int[conditions.size()];
        for (int c = 0; c < conditions.size(); c++) {
            for (PurchasedItem item : items) {
                matched[c] += matchedQuantity(conditions.get(c), item);
            }
        }
        if (!conditions.isEmpty()) {
            boolean ok = event.getConditionMode() == GiftConditionMode.ALL
                    ? Arrays.stream(matched).allMatch(q -> q > 0)
                    : Arrays.stream(matched).anyMatch(q -> q > 0);
            if (!ok) {
                return 0;
            }
        }
        return switch (event.getQuantityMode()) {
            case FIXED -> event.getFixedQty();
            case PER_QUANTITY -> perQuantity(event, conditions, items, matched);
        };
    }

    /**
     * 품목 선택. 결과가 비면 증정하지 않는다 (지급 기록도 남기지 않는다 — 한도를 늘린 뒤 재평가하면 증정된다).
     * <ul>
     *   <li>ALWAYS: 전 품목에 N개씩</li>
     *   <li>SEQUENTIAL: 우선순위 순으로, 남은 한도로 N개를 <b>한 품목에서 전부</b> 충당할 수 있는 첫 품목 1종.
     *       남은 한도가 N보다 작은 품목은 건너뛴다 — 그 잔여는 남는다 (나눠 주지 않는다: 한 주문의 사은품이 섞이지 않게)</li>
     *   <li>RANDOM: N개를 충당할 수 있는 품목 중 1종을 랜덤으로. 소진된 품목은 후보에서 빠진다</li>
     * </ul>
     */
    public static List<Decision> select(Rule rule, int quantity, RandomGenerator random) {
        if (quantity <= 0) {
            return List.of();
        }
        Long eventId = rule.event().getId();
        List<Candidate> ordered = rule.items().stream()
                .sorted(Comparator.comparingInt(Candidate::priority).thenComparing(Candidate::eventItemId))
                .toList();
        return switch (rule.event().getGrantType()) {
            case ALWAYS -> ordered.stream()
                    .map(c -> new Decision(eventId, c.eventItemId(), c.productId(), quantity))
                    .toList();
            case SEQUENTIAL -> ordered.stream()
                    .filter(c -> c.canCover(quantity))
                    .findFirst()
                    .map(c -> List.of(new Decision(eventId, c.eventItemId(), c.productId(), quantity)))
                    .orElse(List.of());
            case RANDOM -> {
                List<Candidate> pool = ordered.stream().filter(c -> c.canCover(quantity)).toList();
                if (pool.isEmpty()) {
                    yield List.of();
                }
                Candidate picked = pool.get(random.nextInt(pool.size()));
                yield List.of(new Decision(eventId, picked.eventItemId(), picked.productId(), quantity));
            }
        };
    }

    /** 판정 + 선택 */
    public static List<Decision> evaluate(Rule rule, OrderFacts order, RandomGenerator random) {
        return select(rule, giftQuantity(rule, order), random);
    }

    static boolean amountMatches(GiftEvent event, BigDecimal paid) {
        if (event.getAmountMin() == null && event.getAmountMax() == null) {
            return true;
        }
        if (paid == null) {
            return false;
        }
        return (event.getAmountMin() == null || paid.compareTo(event.getAmountMin()) >= 0)
                && (event.getAmountMax() == null || paid.compareTo(event.getAmountMax()) < 0);
    }

    /** 조건에 걸린 구매수량. SALE_PRODUCT = 항목 수량, SKU = 그 SKU의 전개 수량(항목 수량 × 구성 수량) */
    static int matchedQuantity(GiftEventCondition condition, PurchasedItem item) {
        if (condition.getTargetType() == GiftConditionTarget.SALE_PRODUCT) {
            boolean match = condition.getSaleProductCode().equals(item.saleProductCode())
                    && (condition.getOptionCode() == null || condition.getOptionCode().equals(item.optionCode()));
            return match ? item.quantity() : 0;
        }
        return item.quantity() * item.skuUnits().getOrDefault(condition.getSku(), 0);
    }

    /**
     * COMBINED: 조건에 걸린 항목들의 수량 합 ÷ unit (한 항목이 여러 조건에 걸려도 한 번만 — 가장 큰 기여분)
     * PER_PRODUCT: 조건별 수량 ÷ unit의 합. 예) 3개마다 1개, A5 + B5 → COMBINED 3, PER_PRODUCT 2
     */
    private static int perQuantity(GiftEvent event, List<GiftEventCondition> conditions, List<PurchasedItem> items,
                                   int[] matched) {
        int unit = event.getPerQtyUnit();
        int shares;
        if (event.getAggregation() == GiftAggregation.COMBINED) {
            int total = 0;
            for (PurchasedItem item : items) {
                total += conditions.stream().mapToInt(c -> matchedQuantity(c, item)).max().orElse(0);
            }
            shares = total / unit;
        } else {
            shares = Arrays.stream(matched).map(q -> q / unit).sum();
        }
        return shares * event.getPerQtyGive();
    }
}
