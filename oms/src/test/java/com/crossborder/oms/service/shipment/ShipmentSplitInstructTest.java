package com.crossborder.oms.service.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crossborder.common.entity.order.ShipmentStatus;
import com.crossborder.common.entity.order.SplitReason;
import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.oms.dto.order.CancellationItemRequest;
import com.crossborder.oms.dto.shipment.ShipmentResponse;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.ForbiddenException;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.order.OrderCancellationService;
import com.crossborder.oms.service.order.OrderRegistrationCommand;
import com.crossborder.oms.service.order.OrderRegistrationCommand.Item;
import com.crossborder.oms.service.order.OrderRegistrationResult;
import com.crossborder.oms.service.order.OrderRegistrationService;
import com.crossborder.oms.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 분리(브랜드·통관 분류 한도·개수 경고·분할 불가·사은품 포함·금액·재분리)와 출고지시(전이·이력·SHIPPING·취소 경합),
 * 분리 → 지시 → 취소 생애 시나리오. 실제 MariaDB + Redis.
 * <p>
 * 통관 분류 SHEET_MASK 한도 120 (V5). 낱장 마스크 환산 1, 10매 박스 환산 10, 토너·크림은 분류 없음.
 */
class ShipmentSplitInstructTest extends IntegrationTestBase {

    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, UserRole.ADMIN, null, null);
    private static final BigDecimal MASK_PRICE = new BigDecimal("200.00");
    private static final BigDecimal BOX_PRICE = new BigDecimal("1800.00");
    private static final BigDecimal TONER_PRICE = new BigDecimal("2000.00");
    private static final BigDecimal SET_PRICE = new BigDecimal("4500.00");

    @Autowired
    private OrderSplitService splitService;
    @Autowired
    private ShipmentInstructService instructService;
    @Autowired
    private OrderCancellationService cancellationService;
    @Autowired
    private OrderRegistrationService registrationService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private long channelId;
    private long companyId;
    private long brandId;
    private long maskProduct;
    private long mask1;
    private long box10;
    private long toner;
    private long set;
    private long maskBig;

    @BeforeEach
    void setUp() {
        channelId = channelId("QOO10");
        companyId = company("분리상사");
        brandId = brand(companyId, "분리브랜드");
        long sheetMask = jdbc.queryForObject("SELECT id FROM customs_categories WHERE code = 'SHEET_MASK'", Long.class);
        maskProduct = product(brandId, "MASK");
        long boxProduct = product(brandId, "MASKBOX10");
        long tonerProduct = product(brandId, "TONER");
        long creamProduct = product(brandId, "CREAM");
        jdbc.update("UPDATE products SET customs_category_id = ?, customs_unit_qty = 1 WHERE id = ?", sheetMask, maskProduct);
        jdbc.update("UPDATE products SET customs_category_id = ?, customs_unit_qty = 10 WHERE id = ?", sheetMask, boxProduct);
        mask1 = saleProduct(brandId, "MASK1");
        composition(mask1, maskProduct, 1, false);
        box10 = saleProduct(brandId, "BOX10");
        composition(box10, boxProduct, 1, false);
        toner = saleProduct(brandId, "TONER");
        composition(toner, tonerProduct, 1, false);
        set = saleProduct(brandId, "SET");
        composition(set, tonerProduct, 1, false);
        composition(set, creamProduct, 1, false);
        composition(set, maskProduct, 1, true);
        maskBig = saleProduct(brandId, "MASK121");
        composition(maskBig, maskProduct, 121, false);
        login(ADMIN);
    }

    // ------------------------------------------------------------------ 분리 규칙

    @Test
    void 단일_브랜드_한도_이내면_회차_1개_사유_없음() {
        long orderId = order("S1", item(toner, 3, TONER_PRICE));

        List<ShipmentResponse> shipments = splitService.split(orderId, ADMIN);

        assertThat(shipments).hasSize(1);
        ShipmentResponse shipment = shipments.getFirst();
        assertThat(shipment.splitReason()).isNull();
        assertThat(shipment.shipmentNo()).isEqualTo(orderNo(orderId) + "-1");
        assertThat(shipment.totalAmount()).isEqualByComparingTo("6000");
        assertThat(shipment.items()).extracting(ShipmentResponse.Item::quantity).containsExactly(3);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.CREATED);
    }

    @Test
    void 멀티브랜드는_브랜드마다_회차를_나누고_BRAND_STAFF는_다른_브랜드_항목이_있으면_분리할_수_없다() {
        long otherBrand = brand(companyId, "타브랜드");
        long orderId = order("S2", item(toner, 1, TONER_PRICE));
        jdbc.update("""
                INSERT INTO order_items (order_id, brand_id, item_type, product_id, quantity, unit_price, status, created_user_id)
                VALUES (?, ?, 'GIFT_PRODUCT', ?, 1, 0, 'ORDERED', 1)
                """, orderId, otherBrand, product(otherBrand, "OTHER"));

        AuthenticatedUser brandStaff = new AuthenticatedUser(1L, UserRole.BRAND_STAFF, companyId, brandId);
        login(brandStaff);
        assertThatThrownBy(() -> splitService.split(orderId, brandStaff)).isInstanceOf(ForbiddenException.class);
        login(ADMIN);

        List<ShipmentResponse> shipments = splitService.split(orderId, ADMIN);
        assertThat(shipments).hasSize(2)
                .allSatisfy(s -> assertThat(s.splitReason()).isEqualTo(SplitReason.BRAND_SPLIT));
        assertThat(shipments).extracting(ShipmentResponse::brandId).containsExactlyInAnyOrder(brandId, otherBrand);
    }

    @Test
    void 시트마스크_분류_한도_120_경계() {
        assertThat(splitService.split(order("M119", item(mask1, 119, MASK_PRICE)), ADMIN)).hasSize(1);
        assertThat(splitService.split(order("M120", item(mask1, 120, MASK_PRICE)), ADMIN)).hasSize(1)
                .allSatisfy(s -> assertThat(s.splitReason()).isNull());

        List<ShipmentResponse> split = splitService.split(order("M121", item(mask1, 121, MASK_PRICE)), ADMIN);
        assertThat(split).hasSize(2).allSatisfy(s -> assertThat(s.splitReason()).isEqualTo(SplitReason.CUSTOMS_LIMIT));
        assertThat(split).extracting(s -> s.items().getFirst().quantity()).containsExactly(120, 1);
    }

    @Test
    void 환산_계수_10매_박스_12개는_120_정확_경계이고_13개면_분할된다() {
        assertThat(splitService.split(order("B12", item(box10, 12, BOX_PRICE)), ADMIN)).hasSize(1);

        List<ShipmentResponse> split = splitService.split(order("B13", item(box10, 13, BOX_PRICE)), ADMIN);
        assertThat(split).extracting(s -> s.items().getFirst().quantity()).containsExactly(12, 1);
    }

    @Test
    void 전체_개수_24_초과는_경고만_하고_분할하지_않는다() {
        ShipmentResponse at24 = splitService.split(order("T24", item(toner, 24, TONER_PRICE)), ADMIN).getFirst();
        assertThat(at24.warnings()).isEmpty();

        List<ShipmentResponse> at25 = splitService.split(order("T25", item(toner, 25, TONER_PRICE)), ADMIN);
        assertThat(at25).hasSize(1);
        assertThat(at25.getFirst().splitReason()).isNull();
        assertThat(at25.getFirst().warnings()).containsExactly("총 수량 25개 — 품목당 24개 규정 확인 필요");
    }

    @Test
    void 분류_한도_분할과_개수_경고가_겹치면_분할은_CUSTOMS_LIMIT_경고는_해당_회차에만() {
        long orderId = order("MT", item(mask1, 121, MASK_PRICE), item(toner, 30, TONER_PRICE));

        List<ShipmentResponse> shipments = splitService.split(orderId, ADMIN);

        // 순차 배분: 마스크 120 → 1회차, 마스크 1 + 토너 30(분류 없음) → 2회차
        assertThat(shipments).hasSize(2).allSatisfy(s -> assertThat(s.splitReason()).isEqualTo(SplitReason.CUSTOMS_LIMIT));
        assertThat(shipments.get(0).warnings()).containsExactly("총 수량 120개 — 품목당 24개 규정 확인 필요");
        assertThat(shipments.get(1).warnings()).containsExactly("총 수량 31개 — 품목당 24개 규정 확인 필요");
    }

    @Test
    void 항목_1개가_이미_분류_한도를_넘으면_분리_실패하고_회차를_만들지_않는다() {
        long orderId = order("BIG", item(maskBig, 1, MASK_PRICE));

        assertThatThrownBy(() -> splitService.split(orderId, ADMIN))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("단일 상품이 통관 한도 초과: MASK-" + suffix + ", 환산수량 121 > 한도 120");
        assertThat(shipmentCount(orderId)).isZero();
    }

    @Test
    void 구성_사은품도_통관_수량에_포함되고_회차_금액은_단가_곱_합이다() {
        long orderId = order("G", item(mask1, 119, MASK_PRICE), item(set, 2, SET_PRICE));

        List<ShipmentResponse> shipments = splitService.split(orderId, ADMIN);

        // 마스크 119 + 세트 1개(구성 사은품 마스크 1) = 120 → 1회차, 세트 1개 → 2회차
        assertThat(shipments).hasSize(2);
        assertThat(shipments.get(0).items()).extracting(ShipmentResponse.Item::quantity).containsExactly(119, 1);
        assertThat(shipments.get(0).totalAmount()).isEqualByComparingTo(
                MASK_PRICE.multiply(BigDecimal.valueOf(119)).add(SET_PRICE));
        assertThat(shipments.get(1).totalAmount()).isEqualByComparingTo(SET_PRICE);
    }

    // ------------------------------------------------------------------ 금액·재분리

    @Test
    void 취소로_CREATED_회차_배정이_줄면_금액을_다시_계산한다() {
        long orderId = order("A", item(toner, 3, TONER_PRICE));
        ShipmentResponse shipment = splitService.split(orderId, ADMIN).getFirst();

        cancel(orderId, itemOf(orderId, toner), 1);

        assertThat(amount(shipment.shipmentId())).isEqualByComparingTo("4000");
    }

    @Test
    void CREATED_회차만_있으면_재분리하고_출고지시된_회차가_있으면_거부한다() {
        long orderId = order("R", item(toner, 3, TONER_PRICE));
        long first = splitService.split(orderId, ADMIN).getFirst().shipmentId();
        cancel(orderId, itemOf(orderId, toner), 1);

        List<ShipmentResponse> resplit = splitService.split(orderId, ADMIN);

        assertThat(shipmentStatus(first)).isEqualTo("CANCELED");
        assertThat(resplit).hasSize(1);
        assertThat(resplit.getFirst().roundNo()).isEqualTo(2);
        assertThat(resplit.getFirst().items().getFirst().quantity()).isEqualTo(2);

        instructService.instruct(resplit.getFirst().shipmentId(), ADMIN);
        assertThatThrownBy(() -> splitService.split(orderId, ADMIN))
                .isInstanceOf(ConflictException.class).hasMessageContaining("출고지시된 회차");
    }

    // ------------------------------------------------------------------ 출고지시

    @Test
    void 출고지시는_회차를_INSTRUCTED로_바꾸고_첫_지시에_주문을_SHIPPING으로_전환하며_이력을_남긴다() {
        long orderId = order("I", item(mask1, 121, MASK_PRICE));
        List<ShipmentResponse> shipments = splitService.split(orderId, ADMIN);
        long round1 = shipments.get(0).shipmentId();
        long round2 = shipments.get(1).shipmentId();

        ShipmentResponse instructed = instructService.instruct(round1, ADMIN);
        instructService.instruct(round2, ADMIN);

        assertThat(instructed.status()).isEqualTo(ShipmentStatus.INSTRUCTED);
        assertThat(jdbc.queryForObject("SELECT instructed_by FROM shipments WHERE id = ?", Long.class, round1)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT instructed_at FROM shipments WHERE id = ?", LocalDateTime.class, round1)).isNotNull();
        assertThat(orderStatus(orderId)).isEqualTo("SHIPPING");
        assertThat(history(orderId, "ORDER")).containsExactly("null>PAID", "PAID>SHIPPING");
        assertThat(history(orderId, "SHIPMENT")).containsExactly(
                "null>CREATED", "null>CREATED", "CREATED>INSTRUCTED", "CREATED>INSTRUCTED");
        assertThatThrownBy(() -> instructService.instruct(round1, ADMIN)).isInstanceOf(ConflictException.class);
    }

    @Test
    void 지시된_회차의_배정분은_취소할_수_없고_금액도_그대로다() {
        long orderId = order("IM", item(toner, 3, TONER_PRICE));
        ShipmentResponse shipment = splitService.split(orderId, ADMIN).getFirst();
        instructService.instruct(shipment.shipmentId(), ADMIN);

        assertThatThrownBy(() -> cancel(orderId, itemOf(orderId, toner), 1))
                .isInstanceOf(ConflictException.class).hasMessageContaining("취소 가능 0개");
        assertThat(amount(shipment.shipmentId())).isEqualByComparingTo("6000");
        assertThat(assigned(shipment.shipmentId())).isEqualTo(3);
    }

    /**
     * 취소가 끝났지만 아직 커밋 전(주문 행 락 보유)인 사이에 출고지시가 들어오는 순서를 강제한다.
     * 주문 행 락이 있으면 지시는 취소 커밋까지 기다렸다가 CANCELED 회차를 보고 거부된다.
     * 락이 없으면 커밋 전 스냅샷(CREATED)을 보고 회차를 INSTRUCTED로 덮어써 둘 다 성공한다 (lost update).
     */
    @Test
    void 취소_커밋_전에_들어온_출고지시는_취소_커밋을_기다렸다가_거부된다() throws Exception {
        long orderId = order("RACE", item(toner, 5, TONER_PRICE));
        long shipmentId = splitService.split(orderId, ADMIN).getFirst().shipmentId();
        long itemId = itemOf(orderId, toner);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch canceledUncommitted = new CountDownLatch(1);
        Future<Boolean> cancel = executor.submit(() -> {
            login(ADMIN);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                cancel(orderId, itemId, 5);
                canceledUncommitted.countDown();
                sleep(500); // 커밋 전 주문 행 락 보유
            });
            return true;
        });
        assertThat(canceledUncommitted.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Boolean> instruct = executor.submit(() -> attempt(new CountDownLatch(0),
                () -> instructService.instruct(shipmentId, ADMIN)));

        assertThat(cancel.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(instruct.get(30, TimeUnit.SECONDS)).as("지시는 취소 커밋 후 CANCELED 회차를 보고 거부돼야 한다").isFalse();
        executor.shutdown();
        assertThat(shipmentStatus(shipmentId)).isEqualTo("CANCELED");
        assertThat(orderStatus(orderId)).isEqualTo("CANCELED");
    }

    /** 반대 순서: 지시가 커밋 전인 사이에 들어온 취소는 지시 커밋을 기다렸다가 INSTRUCTED 배정분을 보고 거부된다 */
    @Test
    void 출고지시_커밋_전에_들어온_취소는_지시_커밋을_기다렸다가_거부된다() throws Exception {
        long orderId = order("RACE2", item(toner, 5, TONER_PRICE));
        long shipmentId = splitService.split(orderId, ADMIN).getFirst().shipmentId();
        long itemId = itemOf(orderId, toner);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch instructedUncommitted = new CountDownLatch(1);
        Future<Boolean> instruct = executor.submit(() -> {
            login(ADMIN);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                instructService.instruct(shipmentId, ADMIN);
                instructedUncommitted.countDown();
                sleep(500);
            });
            return true;
        });
        assertThat(instructedUncommitted.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Boolean> cancel = executor.submit(() -> attempt(new CountDownLatch(0), () -> cancel(orderId, itemId, 5)));

        assertThat(instruct.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(cancel.get(30, TimeUnit.SECONDS)).isFalse();
        executor.shutdown();
        assertThat(shipmentStatus(shipmentId)).isEqualTo("INSTRUCTED");
        assertThat(assigned(shipmentId)).isEqualTo(5);
    }

    // ------------------------------------------------------------------ 생애

    @Test
    void 분리_지시_취소_생애_시나리오() {
        long orderId = order("LIFE", item(mask1, 121, MASK_PRICE));
        long itemId = itemOf(orderId, mask1);
        List<ShipmentResponse> shipments = splitService.split(orderId, ADMIN);
        long round1 = shipments.get(0).shipmentId(); // 120
        long round2 = shipments.get(1).shipmentId(); // 1

        instructService.instruct(round1, ADMIN);

        // 지시된 120개는 취소 불가 — 취소 가능 수량은 CREATED 회차의 1개
        assertThatThrownBy(() -> cancel(orderId, itemId, 2))
                .isInstanceOf(ConflictException.class).hasMessageContaining("취소 가능 1개, 요청 2개");
        cancel(orderId, itemId, 1);

        assertThat(shipmentStatus(round2)).isEqualTo("CANCELED");
        assertThat(assigned(round1)).isEqualTo(120);
        assertThat(amount(round1)).isEqualByComparingTo(MASK_PRICE.multiply(BigDecimal.valueOf(120)));
        assertThat(orderStatus(orderId)).isEqualTo("SHIPPING");
        assertThat(splitService.shipments(orderId)).extracting(ShipmentResponse::status)
                .containsExactly(ShipmentStatus.INSTRUCTED, ShipmentStatus.CANCELED);
        assertThat(allocated(maskProduct)).isEqualTo(120);
    }

    // ------------------------------------------------------------------ fixtures

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean attempt(CountDownLatch start, Runnable action) throws InterruptedException {
        login(ADMIN);
        start.await();
        try {
            action.run();
            return true;
        } catch (ConflictException | IllegalStateException e) {
            return false;
        }
    }

    private void cancel(long orderId, long itemId, int quantity) {
        cancellationService.cancel(orderId, List.of(new CancellationItemRequest(itemId, quantity)), ADMIN);
    }

    private Item item(long saleProductId, int quantity, BigDecimal price) {
        return Item.ofSaleProduct(saleProductId, "Q-" + saleProductId, "", quantity, price);
    }

    private long order(String key, Item... items) {
        List<OrderRegistrationResult> results = registrationService.registerAll(List.of(new OrderRegistrationCommand(
                channelId, suffix + "-" + key, brandId, BigDecimal.ONE, BigDecimal.ONE, "JPY", "주문자", "수취인",
                null, null, "주소", null, LocalDateTime.of(2026, 10, 1, 10, 0), List.of(items))));
        assertThat(results.getFirst().status()).isEqualTo(OrderRegistrationResult.Status.REGISTERED);
        return jdbc.queryForObject("SELECT id FROM orders WHERE channel_order_no = ?", Long.class, suffix + "-" + key);
    }

    private String orderNo(long orderId) {
        return jdbc.queryForObject("SELECT order_no FROM orders WHERE id = ?", String.class, orderId);
    }

    private long itemOf(long orderId, long saleProductId) {
        return jdbc.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND sale_product_id = ? AND status = 'ORDERED'",
                Long.class, orderId, saleProductId);
    }

    private int shipmentCount(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id = ?", Integer.class, orderId);
    }

    private String shipmentStatus(long shipmentId) {
        return jdbc.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, shipmentId);
    }

    private BigDecimal amount(long shipmentId) {
        return jdbc.queryForObject("SELECT total_amount FROM shipments WHERE id = ?", BigDecimal.class, shipmentId);
    }

    private int assigned(long shipmentId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM shipment_items WHERE shipment_id = ?",
                Integer.class, shipmentId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private List<String> history(long orderId, String targetType) {
        return jdbc.queryForList("""
                SELECT CONCAT(COALESCE(previous_status, 'null'), '>', current_status) FROM order_status_history
                WHERE order_id = ? AND target_type = ? ORDER BY id
                """, String.class, orderId, targetType);
    }
}
