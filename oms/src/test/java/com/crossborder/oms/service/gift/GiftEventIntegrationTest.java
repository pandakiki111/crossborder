package com.crossborder.oms.service.gift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crossborder.common.entity.gift.GiftAggregation;
import com.crossborder.common.entity.gift.GiftConditionMode;
import com.crossborder.common.entity.gift.GiftConditionTarget;
import com.crossborder.common.entity.gift.GiftEventStatus;
import com.crossborder.common.entity.gift.GiftGrantType;
import com.crossborder.common.entity.gift.GiftQuantityMode;
import com.crossborder.common.entity.gift.GiftTimeBasis;
import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.oms.dto.gift.GiftEventRequest;
import com.crossborder.oms.dto.gift.GiftEventResponse;
import com.crossborder.oms.dto.order.GiftAddRequest;
import com.crossborder.oms.dto.product.ChannelMappingChangeResponse;
import com.crossborder.oms.dto.product.ChannelMappingRequest;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.order.OrderGiftService;
import com.crossborder.oms.service.order.OrderRegistrationCommand;
import com.crossborder.oms.service.order.OrderRegistrationCommand.Item;
import com.crossborder.oms.service.order.OrderRegistrationService;
import com.crossborder.oms.service.product.ChannelMappingService;
import com.crossborder.oms.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 사은품 이벤트 통합 테스트 (실제 MariaDB·Redis). 실무 예시 그대로: 중단 구간, 수량 비례, 순차 한도와 경합, 멱등,
 * 할당 연동, 수정 불가·중단/재시작, 수동 증정, 미리보기 문장.
 */
class GiftEventIntegrationTest extends IntegrationTestBase {

    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, UserRole.ADMIN, null, null);
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 1, 0, 0);

    @Autowired
    private GiftEventService eventService;
    @Autowired
    private GiftEventApplier applier;
    @Autowired
    private OrderRegistrationService registrationService;
    @Autowired
    private OrderGiftService orderGiftService;
    @Autowired
    private ChannelMappingService channelMappingService;

    private long channelId;
    private long brandId;
    private long toner;
    private long cream;
    private long giftA;
    private long giftB;
    private String setA;
    private String setB;
    private long saleA;
    private long saleB;

    @BeforeEach
    void setUp() {
        channelId = channelId("QOO10");
        brandId = brand(company("이벤트상사"), "이벤트브랜드");
        toner = product(brandId, "EV-TONER");
        cream = product(brandId, "EV-CREAM");
        giftA = product(brandId, "EV-GIFT-A");
        giftB = product(brandId, "EV-GIFT-B");
        saleA = saleProduct(brandId, "EV-A");
        composition(saleA, toner, 1, false);
        saleB = saleProduct(brandId, "EV-B");
        composition(saleB, cream, 1, false);
        setA = "EV-A-" + suffix;
        setB = "EV-B-" + suffix;
        login(ADMIN);
    }

    @Test
    void 중단_구간의_주문은_탈락하고_판정은_처리_시각과_무관하다() {
        GiftEventResponse event = eventService.create(request(GiftGrantType.ALWAYS, fixed(1), at(6), at(20),
                List.of(condition(setA)), List.of(new GiftEventRequest.Item(giftA, null, null))), ADMIN);
        // 운영 이력: 14시 중단 → 16시 재시작 → 18시 최종 중단 (이력 규칙대로 마감 + 신규)
        jdbc.update("UPDATE gift_event_active_periods SET active_to = ? WHERE gift_event_id = ?", at(14), event.id());
        jdbc.update("""
                INSERT INTO gift_event_active_periods (gift_event_id, active_from, active_to, created_user_id)
                VALUES (?, ?, ?, 1)
                """, event.id(), at(16), at(18));
        List<Long> orders = IntStream.of(13, 15, 17, 19).mapToObj(h -> order("H" + h, at(h), sale(saleA, 1))).toList();

        // 처리 시각은 지금(이벤트가 다 끝난 뒤) — 주문 시각만으로 판정된다
        GiftEventApplier.Outcome outcome = applier.apply(orders);

        assertThat(outcome.grants()).isEqualTo(2);
        assertThat(giftQuantities(orders, giftA)).containsExactly(1, 0, 1, 0);
    }

    @Test
    void 수량_비례는_COMBINED_3개_PER_PRODUCT_2개() {
        List<GiftEventRequest.Condition> ab = List.of(condition(setA), condition(setB));
        long combined = eventService.create(request(GiftGrantType.ALWAYS, perQuantity(GiftAggregation.COMBINED),
                at(0), at(24), ab, List.of(new GiftEventRequest.Item(giftA, null, null))), ADMIN).id();
        long perProduct = eventService.create(request(GiftGrantType.ALWAYS, perQuantity(GiftAggregation.PER_PRODUCT),
                at(0), at(24), ab, List.of(new GiftEventRequest.Item(giftB, null, null))), ADMIN).id();
        long orderId = order("PQ", at(10), sale(saleA, 5), sale(saleB, 5));

        applier.apply(List.of(orderId));

        assertThat(eventGift(orderId, combined)).isEqualTo(3);
        assertThat(eventGift(orderId, perProduct)).isEqualTo(2);
    }

    @Test
    void SEQUENTIAL은_N개를_전부_충당할_품목을_고르고_잔여는_남긴다() {
        long eventId = eventService.create(request(GiftGrantType.SEQUENTIAL, fixed(2), at(0), at(24), List.of(),
                List.of(new GiftEventRequest.Item(giftA, 1, 1), new GiftEventRequest.Item(giftB, 2, 100))), ADMIN).id();
        long orderId = order("SEQ", at(10), sale(saleA, 1));

        applier.apply(List.of(orderId));

        assertThat(giftQuantities(List.of(orderId), giftB)).containsExactly(2);
        GiftEventResponse event = eventService.get(eventId, ADMIN);
        assertThat(event.items()).extracting(GiftEventResponse.Item::remaining).containsExactly(1, 98);
    }

    @Test
    void 동시_주문_경합에서도_한도를_넘지_않는다() throws Exception {
        long eventId = eventService.create(request(GiftGrantType.SEQUENTIAL, fixed(1), at(0), at(24), List.of(),
                List.of(new GiftEventRequest.Item(giftA, 1, 5))), ADMIN).id();
        List<Long> orders = IntStream.range(0, 20).mapToObj(i -> order("RACE" + i, at(10), sale(saleA, 1))).toList();

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<GiftEventApplier.Outcome>> futures = new ArrayList<>();
            for (Long orderId : orders) {
                Callable<GiftEventApplier.Outcome> task = () -> applier.apply(List.of(orderId));
                futures.add(executor.submit(task));
            }
            int grants = 0;
            for (Future<GiftEventApplier.Outcome> future : futures) {
                GiftEventApplier.Outcome outcome = future.get();
                assertThat(outcome.failedOrders()).isEmpty();
                grants += outcome.grants();
            }
            assertThat(grants).isEqualTo(5);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT granted_qty FROM gift_event_items WHERE gift_event_id = ?",
                Integer.class, eventId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM order_items WHERE gift_event_id = ?",
                Integer.class, eventId)).isEqualTo(5);
    }

    @Test
    void RANDOM은_소진된_품목을_빼고_고른다() {
        long eventId = eventService.create(request(GiftGrantType.RANDOM, fixed(1), at(0), at(24), List.of(),
                List.of(new GiftEventRequest.Item(giftA, 1, 1), new GiftEventRequest.Item(giftB, 2, null))), ADMIN).id();
        jdbc.update("UPDATE gift_event_items SET granted_qty = 1 WHERE gift_event_id = ? AND product_id = ?", eventId, giftA);
        List<Long> orders = IntStream.range(0, 5).mapToObj(i -> order("RND" + i, at(10), sale(saleA, 1))).toList();

        applier.apply(orders);

        assertThat(giftQuantities(orders, giftB)).containsOnly(1);
        assertThat(giftQuantities(orders, giftA)).containsOnly(0);
    }

    @Test
    void 같은_주문을_다시_평가해도_지급_이력으로_한_번만_증정한다() {
        eventService.create(request(GiftGrantType.ALWAYS, fixed(1), at(0), at(24), List.of(),
                List.of(new GiftEventRequest.Item(giftA, null, null))), ADMIN);
        long orderId = order("IDEM", at(10), sale(saleA, 1));

        assertThat(applier.apply(List.of(orderId)).grants()).isEqualTo(1);
        assertThat(applier.apply(List.of(orderId)).grants()).isZero();
        assertThat(giftQuantities(List.of(orderId), giftA)).containsExactly(1);
    }

    @Test
    void 할당된_주문이면_증정분도_할당한다() {
        eventService.create(request(GiftGrantType.ALWAYS, fixed(2), at(0), at(24), List.of(),
                List.of(new GiftEventRequest.Item(giftA, null, null))), ADMIN);
        long orderId = order("ALLOC", at(10), sale(saleA, 1));
        int before = allocated(giftA);

        applier.apply(List.of(orderId));

        assertThat(allocated(giftA)).isEqualTo(before + 2);
        assertThat(jdbc.queryForObject("SELECT gift_source FROM order_items WHERE order_id = ? AND product_id = ?",
                String.class, orderId, giftA)).isEqualTo("EVENT");
    }

    @Test
    void 지급_이력이_생기면_수정_삭제는_409이고_중단_재시작은_된다() {
        LocalDateTime now = LocalDateTime.now();
        GiftEventRequest request = request(GiftGrantType.ALWAYS, fixed(1), now.minusDays(1), now.plusDays(1), List.of(),
                List.of(new GiftEventRequest.Item(giftA, null, null)));
        long eventId = eventService.create(request, ADMIN).id();
        assertThat(eventService.update(eventId, request, ADMIN).grantCount()).as("지급 전 수정은 된다").isZero();
        applier.apply(List.of(order("LOCK", now.minusHours(1), sale(saleA, 1))));

        assertThatThrownBy(() -> eventService.update(eventId, request, ADMIN))
                .isInstanceOf(ConflictException.class).hasMessageContaining("새 이벤트로 등록");
        assertThatThrownBy(() -> eventService.delete(eventId, ADMIN)).isInstanceOf(ConflictException.class);

        assertThat(eventService.stop(eventId, ADMIN).status()).isEqualTo(GiftEventStatus.STOPPED);
        assertThatThrownBy(() -> eventService.stop(eventId, ADMIN)).isInstanceOf(ConflictException.class);
        GiftEventResponse restarted = eventService.restart(eventId, ADMIN);
        assertThat(restarted.status()).isEqualTo(GiftEventStatus.ACTIVE);
        assertThat(restarted.activePeriods()).hasSize(2);
        assertThat(restarted.activePeriods().getFirst().activeTo()).isNotNull();
    }

    @Test
    void 등록_검증_브랜드_밖_제품과_조건_상품은_400() {
        long otherBrand = brand(company("남의상사"), "남의브랜드");
        long otherProduct = product(otherBrand, "OTHER");

        assertThatThrownBy(() -> eventService.create(request(GiftGrantType.ALWAYS, fixed(1), at(0), at(24), List.of(),
                List.of(new GiftEventRequest.Item(otherProduct, null, null))), ADMIN))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("브랜드의 제품");
        assertThatThrownBy(() -> eventService.create(request(GiftGrantType.ALWAYS, fixed(1), at(0), at(24),
                List.of(condition("NO-SUCH")), List.of(new GiftEventRequest.Item(giftA, null, null))), ADMIN))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("NO-SUCH");
        assertThatThrownBy(() -> eventService.create(request(GiftGrantType.ALWAYS, perQuantity(GiftAggregation.COMBINED),
                at(0), at(24), List.of(), List.of(new GiftEventRequest.Item(giftA, null, null))), ADMIN))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("상품 조건");
    }

    @Test
    void 이벤트_코드는_서버가_GEVT_접두와_혼동_문자_뺀_8자리로_만든다() {
        GiftEventRequest request = request(GiftGrantType.ALWAYS, fixed(1), at(0), at(24), List.of(),
                List.of(new GiftEventRequest.Item(giftA, null, null)));
        GiftEventResponse first = eventService.create(request, ADMIN);
        GiftEventResponse second = eventService.create(request, ADMIN);

        assertThat(first.code()).matches("GEVT-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}");
        assertThat(second.code()).isNotEqualTo(first.code());
        assertThat(eventService.update(first.id(), request, ADMIN).code()).as("수정해도 불변").isEqualTo(first.code());
    }

    @Test
    void 매핑안됨_주문도_판정하고_매핑안됨_항목은_조건에서만_빠진다() {
        long byProduct = eventService.create(request(GiftGrantType.ALWAYS, fixed(1), at(0), at(24),
                List.of(condition(setA)), List.of(new GiftEventRequest.Item(giftA, null, null))), ADMIN).id();
        long byAmount = eventService.create(new GiftEventRequest(brandId, "금액-" + suffix, GiftTimeBasis.ORDERED,
                at(0), at(24), BigDecimal.valueOf(5000), null, GiftConditionMode.ALL, GiftGrantType.ALWAYS,
                GiftQuantityMode.FIXED, 1, null, null, null, List.of(),
                List.of(new GiftEventRequest.Item(giftB, null, null))), ADMIN).id();
        // 판매상품 A 채널코드가 아직 매핑되지 않아 매핑안됨 항목으로 들어온 주문
        long orderId = order("PENDING", at(10), Item.ofSaleProduct(null, "Q-" + setA, "", 1, BigDecimal.valueOf(1000)));
        int before = allocated(giftB);

        applier.apply(List.of(orderId));

        assertThat(eventGift(orderId, byProduct)).as("매핑안됨 항목은 조건 매칭 제외").isZero();
        assertThat(eventGift(orderId, byAmount)).as("금액 조건은 정상 판정").isEqualTo(1);
        assertThat(allocated(giftB)).as("매핑안됨 주문은 미할당").isEqualTo(before);

        ChannelMappingChangeResponse mapped = channelMappingService.create(
                new ChannelMappingRequest(channelId, "Q-" + setA, "", saleA), ADMIN);
        assertThat(mapped.completedOrderCount()).isEqualTo(1);
        assertThat(mapped.giftEventNotice()).isEqualTo(ChannelMappingChangeResponse.GIFT_EVENT_NOTICE);
        assertThat(eventGift(orderId, byProduct)).as("매핑 후 재평가 없음").isZero();
        assertThat(allocated(giftB)).as("매핑 완료 할당에 사은품 행이 함께 들어간다").isEqualTo(before + 1);
    }

    @Test
    void 판매상품코드_조건은_이벤트_브랜드_안에서만_매칭한다() {
        // 다른 브랜드에 같은 판매상품코드 (브랜드 내 유니크라 허용, V10)
        long otherBrand = brand(company("남의상사"), "남의브랜드");
        long otherProduct = product(otherBrand, "OTHER-TONER");
        long otherSale = saleProduct(otherBrand, "EV-A");
        composition(otherSale, otherProduct, 1, false);
        assertThat(jdbc.queryForObject("SELECT code FROM sale_products WHERE id = ?", String.class, otherSale)).isEqualTo(setA);
        long eventId = eventService.create(request(GiftGrantType.ALWAYS, fixed(1), at(0), at(24), List.of(condition(setA)),
                List.of(new GiftEventRequest.Item(giftA, null, null))), ADMIN).id();
        registrationService.registerAll(List.of(new OrderRegistrationCommand(channelId, suffix + "-OTHER", otherBrand,
                BigDecimal.valueOf(5000), BigDecimal.valueOf(5000), "JPY", "주문자", "수취인", null, null, "주소", null,
                at(10), List.of(sale(otherSale, 1)))));
        long otherOrder = jdbc.queryForObject("SELECT id FROM orders WHERE channel_order_no = ?", Long.class, suffix + "-OTHER");
        long ownOrder = order("OWN", at(10), sale(saleA, 1));

        applier.apply(List.of(otherOrder, ownOrder));

        assertThat(eventGift(otherOrder, eventId)).as("타 브랜드 동일 코드 오매칭 없음").isZero();
        assertThat(eventGift(ownOrder, eventId)).isEqualTo(1);
    }

    @Test
    void 미리보기는_설정을_운영자_문장으로_옮긴다() {
        GiftEventRequest request = new GiftEventRequest(brandId, "10월 이벤트", GiftTimeBasis.ORDERED, at(6), at(20),
                BigDecimal.valueOf(100_000), null, GiftConditionMode.ALL, GiftGrantType.SEQUENTIAL,
                GiftQuantityMode.FIXED, 1, null, null, null, List.of(condition(setA), condition(setB)),
                List.of(new GiftEventRequest.Item(giftA, 1, 100)));

        assertThat(eventService.create(request, ADMIN).preview()).isEqualTo(
                "주문시간 기준 10/1 06:00~20:00, 상품 " + setA + "·" + setB + " 모두 구매, 결제금액 100,000 이상 주문에 "
                        + "EV-GIFT-A-" + suffix + " 1개 — 선착순 100개");
    }

    @Test
    void 수동_증정은_MANUAL로_추가되고_할당되며_취소된_주문은_409() {
        long orderId = order("MANUAL", at(10), sale(saleA, 1));
        int before = allocated(giftB);

        long itemId = orderGiftService.addGift(orderId, new GiftAddRequest(giftB, 3), ADMIN);

        assertThat(jdbc.queryForObject("SELECT gift_source FROM order_items WHERE id = ?", String.class, itemId))
                .isEqualTo("MANUAL");
        assertThat(allocated(giftB)).isEqualTo(before + 3);

        jdbc.update("UPDATE orders SET status = 'CANCELED' WHERE id = ?", orderId);
        assertThatThrownBy(() -> orderGiftService.addGift(orderId, new GiftAddRequest(giftB, 1), ADMIN))
                .isInstanceOf(ConflictException.class);
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

    private GiftEventRequest request(GiftGrantType grantType, Quantity q, LocalDateTime from, LocalDateTime to,
                                     List<GiftEventRequest.Condition> conditions, List<GiftEventRequest.Item> items) {
        return new GiftEventRequest(brandId, "이벤트-" + suffix, GiftTimeBasis.ORDERED, from, to, null, null,
                GiftConditionMode.ALL, grantType, q.mode(), q.fixed(), q.unit(), q.give(), q.aggregation(),
                conditions, items);
    }

    private static GiftEventRequest.Condition condition(String saleProductCode) {
        return new GiftEventRequest.Condition(GiftConditionTarget.SALE_PRODUCT, saleProductCode, null, null);
    }

    private long order(String key, LocalDateTime orderedAt, Item... items) {
        registrationService.registerAll(List.of(new OrderRegistrationCommand(channelId, suffix + "-" + key, brandId,
                BigDecimal.valueOf(5000), BigDecimal.valueOf(5000), "JPY", "주문자", "수취인", null, null, "주소", null,
                orderedAt, List.of(items))));
        return jdbc.queryForObject("SELECT id FROM orders WHERE channel_order_no = ?", Long.class, suffix + "-" + key);
    }

    private static Item sale(long saleProductId, int quantity) {
        return Item.ofSaleProduct(saleProductId, "CODE", "", quantity, BigDecimal.valueOf(1000));
    }

    /** 주문별 그 제품의 EVENT 사은품 수량 (없으면 0) */
    private List<Integer> giftQuantities(List<Long> orderIds, long productId) {
        return orderIds.stream().map(orderId -> jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM order_items
                WHERE order_id = ? AND product_id = ? AND gift_source = 'EVENT'
                """, Integer.class, orderId, productId)).toList();
    }

    private int eventGift(long orderId, long eventId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM order_items WHERE order_id = ? AND gift_event_id = ?",
                Integer.class, orderId, eventId);
    }

    private static LocalDateTime at(int hour) {
        return DAY.plusHours(hour);
    }
}
