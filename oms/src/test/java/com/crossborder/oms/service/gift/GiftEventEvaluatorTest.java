package com.crossborder.oms.service.gift;

import static org.assertj.core.api.Assertions.assertThat;

import com.crossborder.common.entity.gift.GiftAggregation;
import com.crossborder.common.entity.gift.GiftConditionMode;
import com.crossborder.common.entity.gift.GiftEvent;
import com.crossborder.common.entity.gift.GiftEventActivePeriod;
import com.crossborder.common.entity.gift.GiftEventCondition;
import com.crossborder.common.entity.gift.GiftGrantType;
import com.crossborder.common.entity.gift.GiftQuantityMode;
import com.crossborder.common.entity.gift.GiftTimeBasis;
import com.crossborder.oms.service.gift.GiftEventEvaluator.Candidate;
import com.crossborder.oms.service.gift.GiftEventEvaluator.Decision;
import com.crossborder.oms.service.gift.GiftEventEvaluator.OrderFacts;
import com.crossborder.oms.service.gift.GiftEventEvaluator.PurchasedItem;
import com.crossborder.oms.service.gift.GiftEventEvaluator.Rule;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 판정 파이프라인 단위 테스트 (순수 로직). 실무 예시를 그대로 옮겼다.
 */
class GiftEventEvaluatorTest {

    private static final long BRAND = 7L;
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 1, 0, 0);

    @Test
    void 중단_구간_주문은_탈락하고_처리_시각과_무관하다() {
        // 6~20시 이벤트, [14,16) 중단 후 16시 재시작, 18시 최종 중단
        GiftEventActivePeriod first = GiftEventActivePeriod.open(1L, at(6));
        first.close(at(14));
        GiftEventActivePeriod second = GiftEventActivePeriod.open(1L, at(16));
        second.close(at(18));
        Rule rule = rule(event(GiftGrantType.ALWAYS, fixed(1)), List.of(sale("A", null)), List.of(first, second),
                List.of(item(1, null)));

        assertThat(IntStream.of(13, 15, 17, 19).map(h -> GiftEventEvaluator.giftQuantity(rule, order(at(h), sale("A", 1)))))
                .containsExactly(1, 0, 1, 0);
    }

    @Test
    void 결제시간_기준은_결제_시각으로_판정하고_없으면_주문_시각으로() {
        Rule rule = rule(event(GiftGrantType.ALWAYS, fixed(1), GiftTimeBasis.PAID), List.of(), openPeriod(),
                List.of(item(1, null)));
        OrderFacts paidLate = new OrderFacts(1L, at(19), at(21), amount(1000), Set.of(BRAND), List.of(purchase("A", "", 1)));
        OrderFacts noPaidAt = new OrderFacts(2L, at(19), null, amount(1000), Set.of(BRAND), List.of(purchase("A", "", 1)));

        assertThat(GiftEventEvaluator.giftQuantity(rule, paidLate)).as("결제 21시 > 이벤트 끝 20시").isZero();
        assertThat(GiftEventEvaluator.giftQuantity(rule, noPaidAt)).isEqualTo(1);
    }

    @Test
    void PER_QUANTITY는_COMBINED면_합산_몫_PER_PRODUCT면_상품별_몫의_합() {
        // 3개마다 1개, A5 + B5
        List<GiftEventCondition> conditions = List.of(sale("A", null), sale("B", null));
        OrderFacts order = order(at(10), purchase("A", "", 5), purchase("B", "", 5));

        Rule combined = rule(event(GiftGrantType.ALWAYS, perQuantity(GiftAggregation.COMBINED)), conditions,
                openPeriod(), List.of(item(1, null)));
        Rule perProduct = rule(event(GiftGrantType.ALWAYS, perQuantity(GiftAggregation.PER_PRODUCT)), conditions,
                openPeriod(), List.of(item(1, null)));

        assertThat(GiftEventEvaluator.giftQuantity(combined, order)).isEqualTo(3);
        assertThat(GiftEventEvaluator.giftQuantity(perProduct, order)).isEqualTo(2);
    }

    @Test
    void SEQUENTIAL은_N개를_한_품목으로_전부_충당할_수_있는_최선순위를_고르고_잔여는_남긴다() {
        // A 잔여 1, B 잔여 100, 증정 2개 → B 2개 (A의 1개는 남는다)
        Rule rule = rule(event(GiftGrantType.SEQUENTIAL, fixed(2)), List.of(), openPeriod(),
                List.of(new Candidate(1L, 101L, 1, 1), new Candidate(2L, 102L, 2, 100)));

        assertThat(GiftEventEvaluator.evaluate(rule, order(at(10)), new Random(1)))
                .containsExactly(new Decision(null, 2L, 102L, 2));
    }

    @Test
    void SEQUENTIAL에서_모든_품목이_모자라면_증정하지_않는다() {
        Rule rule = rule(event(GiftGrantType.SEQUENTIAL, fixed(2)), List.of(), openPeriod(),
                List.of(new Candidate(1L, 101L, 1, 1), new Candidate(2L, 102L, 2, 0)));

        assertThat(GiftEventEvaluator.evaluate(rule, order(at(10)), new Random(1))).isEmpty();
    }

    @Test
    void RANDOM은_시드가_같으면_같은_결과이고_소진된_품목은_풀에서_빠진다() {
        List<Candidate> items = List.of(new Candidate(1L, 101L, 1, null), new Candidate(2L, 102L, 2, null),
                new Candidate(3L, 103L, 3, null));
        Rule rule = rule(event(GiftGrantType.RANDOM, fixed(1)), List.of(), openPeriod(), items);

        List<Long> first = IntStream.range(0, 20).mapToObj(i -> pick(rule, new Random(42L + i))).toList();
        List<Long> again = IntStream.range(0, 20).mapToObj(i -> pick(rule, new Random(42L + i))).toList();
        assertThat(first).isEqualTo(again).contains(101L, 102L, 103L);

        Rule exhausted = rule(event(GiftGrantType.RANDOM, fixed(1)), List.of(), openPeriod(),
                List.of(new Candidate(1L, 101L, 1, 0), new Candidate(2L, 102L, 2, null), new Candidate(3L, 103L, 3, 0)));
        assertThat(IntStream.range(0, 20).mapToObj(i -> pick(exhausted, new Random(i)))).containsOnly(102L);
    }

    @Test
    void ALWAYS는_전_품목에_N개씩() {
        Rule rule = rule(event(GiftGrantType.ALWAYS, fixed(2)), List.of(), openPeriod(),
                List.of(new Candidate(1L, 101L, 1, null), new Candidate(2L, 102L, 2, null)));

        assertThat(GiftEventEvaluator.evaluate(rule, order(at(10)), new Random(1)))
                .containsExactly(new Decision(null, 1L, 101L, 2), new Decision(null, 2L, 102L, 2));
    }

    @Test
    void 옵션코드가_있으면_옵션까지_일치해야_하고_없으면_옵션_무관() {
        Rule withOption = rule(event(GiftGrantType.ALWAYS, fixed(1)), List.of(sale("A", "10P")), openPeriod(),
                List.of(item(1, null)));
        Rule anyOption = rule(event(GiftGrantType.ALWAYS, fixed(1)), List.of(sale("A", null)), openPeriod(),
                List.of(item(1, null)));

        assertThat(GiftEventEvaluator.giftQuantity(withOption, order(at(10), purchase("A", "10P", 1)))).isEqualTo(1);
        assertThat(GiftEventEvaluator.giftQuantity(withOption, order(at(10), purchase("A", "5P", 1)))).isZero();
        assertThat(GiftEventEvaluator.giftQuantity(anyOption, order(at(10), purchase("A", "5P", 1)))).isEqualTo(1);
    }

    @Test
    void ALL은_조건_전부_ANY는_하나_이상() {
        List<GiftEventCondition> ab = List.of(sale("A", null), sale("B", null));
        Rule all = rule(event(GiftGrantType.ALWAYS, fixed(1)), ab, openPeriod(), List.of(item(1, null)));
        Rule any = rule(eventWith(GiftConditionMode.ANY), ab, openPeriod(), List.of(item(1, null)));

        assertThat(GiftEventEvaluator.giftQuantity(all, order(at(10), purchase("A", "", 1)))).isZero();
        assertThat(GiftEventEvaluator.giftQuantity(all, order(at(10), purchase("A", "", 1), purchase("B", "", 1)))).isEqualTo(1);
        assertThat(GiftEventEvaluator.giftQuantity(any, order(at(10), purchase("A", "", 1)))).isEqualTo(1);
    }

    @Test
    void 결제금액은_하한_포함_상한_미포함() {
        GiftEvent event = GiftEvent.create(BRAND, new GiftEvent.Definition("금액", GiftTimeBasis.ORDERED, at(0), at(24),
                amount(10_000), amount(50_000), GiftConditionMode.ALL, GiftGrantType.ALWAYS, GiftQuantityMode.FIXED,
                1, null, null, null));
        Rule rule = rule(event, List.of(), openPeriod(), List.of(item(1, null)));

        assertThat(List.of(9_999, 10_000, 49_999, 50_000).stream()
                .map(paid -> GiftEventEvaluator.giftQuantity(rule, new OrderFacts(1L, at(10), null, amount(paid),
                        Set.of(BRAND), List.of()))))
                .containsExactly(0, 1, 1, 0);
        assertThat(GiftEventEvaluator.giftQuantity(rule, new OrderFacts(1L, at(10), null, null, Set.of(BRAND), List.of())))
                .as("결제금액 없음").isZero();
    }

    @Test
    void SKU_조건은_구성에_그_SKU가_든_판매상품_구매이고_수량은_전개_수량() {
        Rule rule = rule(event(GiftGrantType.ALWAYS, perQuantity(GiftAggregation.COMBINED)),
                List.of(GiftEventCondition.ofSku(1L, "SKU-MASK")), openPeriod(), List.of(item(1, null)));
        // 마스크 2개가 든 세트 2개 = 마스크 4개 → 3개마다 1개 = 1
        PurchasedItem set = new PurchasedItem(BRAND, "SET", "", 2, Map.of("SKU-TONER", 1, "SKU-MASK", 2));
        PurchasedItem other = new PurchasedItem(BRAND, "TONER", "", 9, Map.of("SKU-TONER", 1));

        assertThat(GiftEventEvaluator.giftQuantity(rule, order(at(10), set))).isEqualTo(1);
        assertThat(GiftEventEvaluator.giftQuantity(rule, order(at(10), other))).isZero();
    }

    @Test
    void 이벤트_브랜드_항목이_없는_주문은_상품_조건이_없어도_대상이_아니다() {
        Rule amountOnly = rule(event(GiftGrantType.ALWAYS, fixed(1)), List.of(), openPeriod(), List.of(item(1, null)));

        assertThat(GiftEventEvaluator.giftQuantity(amountOnly,
                new OrderFacts(1L, at(10), null, amount(1000), Set.of(BRAND + 1), List.of()))).isZero();
        assertThat(GiftEventEvaluator.giftQuantity(amountOnly,
                new OrderFacts(1L, at(10), null, amount(1000), Set.of(BRAND), List.of())))
                .as("매핑안됨 항목만 있는 주문도 브랜드는 있다").isEqualTo(1);
    }

    @Test
    void 다른_브랜드_항목은_조건에_걸리지_않는다() {
        Rule rule = rule(event(GiftGrantType.ALWAYS, fixed(1)), List.of(sale("A", null)), openPeriod(),
                List.of(item(1, null)));
        PurchasedItem otherBrand = new PurchasedItem(BRAND + 1, "A", "", 1, Map.of());

        assertThat(GiftEventEvaluator.giftQuantity(rule,
                new OrderFacts(1L, at(10), null, amount(1000), Set.of(BRAND, BRAND + 1), List.of(otherBrand)))).isZero();
    }

    // ------------------------------------------------------------------ fixtures

    private record Quantity(GiftQuantityMode mode, Integer fixed, Integer unit, Integer give, GiftAggregation aggregation) {
    }

    private static Quantity fixed(int n) {
        return new Quantity(GiftQuantityMode.FIXED, n, null, null, null);
    }

    private static Quantity perQuantity(GiftAggregation aggregation) {
        return new Quantity(GiftQuantityMode.PER_QUANTITY, null, 3, 1, aggregation);
    }

    private static GiftEvent event(GiftGrantType grantType, Quantity q) {
        return event(grantType, q, GiftTimeBasis.ORDERED);
    }

    private static GiftEvent event(GiftGrantType grantType, Quantity q, GiftTimeBasis basis) {
        return GiftEvent.create(BRAND, new GiftEvent.Definition("이벤트", basis, at(6), at(20), null, null,
                GiftConditionMode.ALL, grantType, q.mode(), q.fixed(), q.unit(), q.give(), q.aggregation()));
    }

    private static GiftEvent eventWith(GiftConditionMode mode) {
        return GiftEvent.create(BRAND, new GiftEvent.Definition("이벤트", GiftTimeBasis.ORDERED, at(6), at(20), null,
                null, mode, GiftGrantType.ALWAYS, GiftQuantityMode.FIXED, 1, null, null, null));
    }

    private static Rule rule(GiftEvent event, List<GiftEventCondition> conditions, List<GiftEventActivePeriod> periods,
                             List<Candidate> items) {
        return new Rule(event, conditions, periods, items);
    }

    private static List<GiftEventActivePeriod> openPeriod() {
        return List.of(GiftEventActivePeriod.open(1L, at(0)));
    }

    private static GiftEventCondition sale(String code, String option) {
        return GiftEventCondition.ofSaleProduct(1L, code, option);
    }

    private static Candidate item(long id, Integer remaining) {
        return new Candidate(id, 100L + id, (int) id, remaining);
    }

    private static PurchasedItem purchase(String code, String option, int quantity) {
        return new PurchasedItem(BRAND, code, option, quantity, Map.of());
    }

    private static PurchasedItem sale(String code, int quantity) {
        return purchase(code, "", quantity);
    }

    private static OrderFacts order(LocalDateTime orderedAt, PurchasedItem... items) {
        return new OrderFacts(1L, orderedAt, null, amount(1000), Set.of(BRAND), List.of(items));
    }

    private static Long pick(Rule rule, Random random) {
        return GiftEventEvaluator.evaluate(rule, order(at(10)), random).getFirst().productId();
    }

    private static LocalDateTime at(int hour) {
        return DAY.plusHours(hour);
    }

    private static BigDecimal amount(long value) {
        return BigDecimal.valueOf(value);
    }
}
