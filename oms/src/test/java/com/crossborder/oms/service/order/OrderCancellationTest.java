package com.crossborder.oms.service.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.oms.dto.order.CancellationItemRequest;
import com.crossborder.oms.dto.order.CancellationResponse;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.ForbiddenException;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.order.OrderRegistrationCommand.Item;
import com.crossborder.oms.service.stock.AllocationAdminService;
import com.crossborder.oms.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 부분취소: 수량 판정(미배정·CREATED·INSTRUCTED 경계), 감량 순서, 행 분할과 할당 해제 일치, 빈 회차 취소,
 * 주문 상태 재계산·이력, 원자성(스코프·수량), 매핑안됨 해소, 동시 취소 × 동시 할당 정합.
 * <p>
 * 회차는 분리 기능이 아직 없어 SQL로 직접 만든다. SET 판매상품 = 토너 x1 + 크림 x1 + 마스크 x1(구성 사은품).
 */
class OrderCancellationTest extends IntegrationTestBase {

    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, UserRole.ADMIN, null, null);
    private static final LocalDateTime ORDERED_AT = LocalDateTime.of(2026, 10, 1, 10, 0);

    @Autowired
    private OrderCancellationService cancellationService;
    @Autowired
    private OrderRegistrationService registrationService;
    @Autowired
    private AllocationAdminService allocationAdminService;

    private long channelId;
    private long companyId;
    private long brandId;
    private long toner;
    private long cream;
    private long mask;
    private long setProduct;

    @BeforeEach
    void setUp() {
        channelId = channelId("QOO10");
        companyId = company("취소상사");
        brandId = brand(companyId, "취소브랜드");
        toner = product(brandId, "TONER");
        cream = product(brandId, "CREAM");
        mask = product(brandId, "MASK");
        setProduct = saleProduct(brandId, "SET");
        composition(setProduct, toner, 1, false);
        composition(setProduct, cream, 1, false);
        composition(setProduct, mask, 1, true);
        login(ADMIN);
    }

    // ------------------------------------------------------------------ 수량 판정

    @Test
    void 미배정_수량_부분취소는_행을_분할하고_전개분만큼_할당을_해제한다() {
        long orderId = order("A", set(5));
        long itemId = itemOf(orderId);

        CancellationResponse response = cancel(orderId, itemId, 2);

        assertThat(quantity(itemId)).isEqualTo(3);
        assertThat(status(itemId)).isEqualTo("ORDERED");
        long canceledRow = response.items().getFirst().canceledOrderItemId();
        assertThat(canceledRow).isNotEqualTo(itemId);
        assertThat(quantity(canceledRow)).isEqualTo(2);
        assertThat(status(canceledRow)).isEqualTo("CANCELED");
        assertThat(allocated(toner)).isEqualTo(3);
        assertThat(allocated(cream)).isEqualTo(3);
        assertThat(allocated(mask)).isEqualTo(3);
        assertThat(orderStatus(orderId)).isEqualTo("PARTIAL_CANCELED");
        assertThat(orderHistory(orderId)).containsExactly("null>PAID", "PAID>PARTIAL_CANCELED");
    }

    @Test
    void CREATED_회차_배정분까지_전량_취소하면_회차가_비어_취소되고_주문도_취소된다() {
        long orderId = order("B", set(5));
        long itemId = itemOf(orderId);
        long shipment = shipment(orderId, 1, "CREATED");
        assign(shipment, itemId, 5);

        CancellationResponse response = cancel(orderId, itemId, 5);

        assertThat(status(itemId)).isEqualTo("CANCELED");
        assertThat(assigned(shipment, itemId)).isZero();
        assertThat(shipmentStatus(shipment)).isEqualTo("CANCELED");
        assertThat(response.canceledShipmentIds()).containsExactly(shipment);
        assertThat(orderStatus(orderId)).isEqualTo("CANCELED");
        assertThat(allocated(toner)).isZero();
        assertThat(allocated(mask)).isZero();
        assertThat(orderHistory(orderId)).containsExactly("null>PAID", "PAID>CANCELED");
        assertThat(shipmentHistory(shipment)).containsExactly("CREATED>CANCELED");
    }

    @Test
    void 출고지시된_회차_배정분은_취소할_수_없고_아무것도_바뀌지_않는다() {
        long orderId = order("C", set(5));
        long itemId = itemOf(orderId);
        assign(shipment(orderId, 1, "INSTRUCTED"), itemId, 5);

        assertThatThrownBy(() -> cancel(orderId, itemId, 1))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("취소 가능 0개");

        assertThat(quantity(itemId)).isEqualTo(5);
        assertThat(allocated(toner)).isEqualTo(5);
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
    }

    @Test
    void 출고지시_3_CREATED_2이면_2개까지_취소되고_3개는_거부된다() {
        long orderId = order("D", set(5));
        long itemId = itemOf(orderId);
        long instructed = shipment(orderId, 1, "INSTRUCTED");
        long created = shipment(orderId, 2, "CREATED");
        assign(instructed, itemId, 3);
        assign(created, itemId, 2);

        assertThatThrownBy(() -> cancel(orderId, itemId, 3))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("취소 가능 2개, 요청 3개");
        assertThat(quantity(itemId)).isEqualTo(5);

        cancel(orderId, itemId, 2);

        assertThat(quantity(itemId)).isEqualTo(3);
        assertThat(assigned(instructed, itemId)).isEqualTo(3);
        assertThat(shipmentStatus(created)).isEqualTo("CANCELED");
        assertThat(shipmentStatus(instructed)).isEqualTo("INSTRUCTED");
    }

    // ------------------------------------------------------------------ 감량 순서

    @Test
    void 미배정_수량부터_소진하고_CREATED_회차는_번호_역순으로_감량한다() {
        long orderId = order("E", set(6));
        long itemId = itemOf(orderId);
        long round1 = shipment(orderId, 1, "CREATED");
        long round2 = shipment(orderId, 2, "CREATED");
        assign(round1, itemId, 2);
        assign(round2, itemId, 3); // 미배정 1

        cancel(orderId, itemId, 3); // 미배정 1 + round2에서 2

        assertThat(quantity(itemId)).isEqualTo(3);
        assertThat(assigned(round2, itemId)).isEqualTo(1);
        assertThat(assigned(round1, itemId)).isEqualTo(2);

        cancel(orderId, itemId, 2); // round2 1(비움) + round1에서 1

        assertThat(quantity(itemId)).isEqualTo(1);
        assertThat(shipmentStatus(round2)).isEqualTo("CANCELED");
        assertThat(assigned(round1, itemId)).isEqualTo(1);
        assertThat(shipmentStatus(round1)).isEqualTo("CREATED");
        assertThat(allocated(toner)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 원자성·스코프·항목 규칙

    @Test
    void 한_항목이라도_거부되면_다른_항목도_취소되지_않는다() {
        long orderId = order("F", set(2), Item.ofGift(mask, "MASK-SKU", "", 1, BigDecimal.ZERO));
        long setItem = itemOf(orderId);
        long giftItem = giftItemOf(orderId);
        assign(shipment(orderId, 1, "INSTRUCTED"), setItem, 2);

        assertThatThrownBy(() -> cancellationService.cancel(orderId,
                List.of(new CancellationItemRequest(giftItem, 1), new CancellationItemRequest(setItem, 1)), ADMIN))
                .isInstanceOf(ConflictException.class);

        assertThat(status(giftItem)).isEqualTo("ORDERED");
        assertThat(allocated(mask)).isEqualTo(2 + 1);
    }

    @Test
    void 다른_브랜드_항목이_섞이면_403으로_전체_거부하고_자기_브랜드_항목만이면_취소된다() {
        long otherBrand = brand(companyId, "타브랜드");
        long orderId = order("G", set(2));
        long ownItem = itemOf(orderId);
        long otherItem = insert("""
                INSERT INTO order_items (order_id, brand_id, item_type, product_id, quantity, status, created_user_id)
                VALUES (?, ?, 'GIFT_PRODUCT', ?, 1, 'ORDERED', 1)
                """, orderId, otherBrand, product(otherBrand, "OTHER"));
        long brandStaffId = insert("""
                INSERT INTO users (login_id, email, password, name, role, company_id, brand_id)
                VALUES (?, ?, '!', '브랜드직원', 'BRAND_STAFF', ?, ?)
                """, "bs-" + suffix + "@test.local", "bs-" + suffix + "@test.local", companyId, brandId);
        AuthenticatedUser brandStaff = new AuthenticatedUser(brandStaffId, UserRole.BRAND_STAFF, companyId, brandId);
        login(brandStaff);

        assertThatThrownBy(() -> cancellationService.cancel(orderId,
                List.of(new CancellationItemRequest(ownItem, 1), new CancellationItemRequest(otherItem, 1)), brandStaff))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("orderItemId=" + otherItem);
        assertThat(quantity(ownItem)).isEqualTo(2);

        cancellationService.cancel(orderId, List.of(new CancellationItemRequest(ownItem, 1)), brandStaff);
        assertThat(quantity(ownItem)).isEqualTo(1);
    }

    @Test
    void 사은품은_전체_취소만_되고_이미_취소된_항목은_거부된다() {
        long orderId = order("H", set(1), Item.ofGift(mask, "MASK-SKU", "", 2, BigDecimal.ZERO));
        long giftItem = giftItemOf(orderId);

        assertThatThrownBy(() -> cancel(orderId, giftItem, 1))
                .isInstanceOf(ConflictException.class).hasMessageContaining("사은품");

        cancel(orderId, giftItem, 2);
        assertThat(status(giftItem)).isEqualTo("CANCELED");
        assertThat(allocated(mask)).isEqualTo(1);

        assertThatThrownBy(() -> cancel(orderId, giftItem, 2))
                .isInstanceOf(ConflictException.class).hasMessageContaining("이미 취소된");
    }

    @Test
    void 매핑안됨_항목을_취소해_매핑안됨이_해소되면_남은_항목을_할당한다() {
        long orderId = order("I", set(1), Item.ofSaleProduct(null, "Q-NEW-" + suffix, "", 2, price(3000)));
        assertThat(allocated(toner)).isZero();
        long unmapped = jdbc.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND sale_product_id IS NULL", Long.class, orderId);

        cancel(orderId, unmapped, 2);

        assertThat(jdbc.queryForObject("SELECT mapping_pending FROM orders WHERE id = ?", Boolean.class, orderId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT allocated_at FROM orders WHERE id = ?", LocalDateTime.class, orderId)).isNotNull();
        assertThat(allocated(toner)).isEqualTo(1);
        assertThat(allocated(mask)).isEqualTo(1);
        assertThat(orderStatus(orderId)).isEqualTo("PARTIAL_CANCELED");
    }

    @Test
    void 매핑안됨_주문을_전량_취소하면_할당하지_않고_allocated_at도_기록하지_않는다() {
        long orderId = order("IC", set(1), Item.ofSaleProduct(null, "Q-NEW-" + suffix, "", 2, price(3000)));
        long setItem = itemOf(orderId);
        long unmapped = jdbc.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND sale_product_id IS NULL", Long.class, orderId);

        cancellationService.cancel(orderId,
                List.of(new CancellationItemRequest(setItem, 1), new CancellationItemRequest(unmapped, 2)), ADMIN);

        assertThat(orderStatus(orderId)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT mapping_pending FROM orders WHERE id = ?", Boolean.class, orderId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT allocated_at FROM orders WHERE id = ?", LocalDateTime.class, orderId)).isNull();
        assertThat(allocated(toner)).isZero();
        assertThat(allocated(mask)).isZero();
    }

    // ------------------------------------------------------------------ 동시성

    @Test
    void 동시_취소와_동시_할당이_겹쳐도_할당재고는_유효_항목_전개_합과_같다() throws Exception {
        int cancelThreads = 5;
        int cancelsPerThread = 4;
        int registerThreads = 3;
        int ordersPerRegister = 10;
        List<long[]> targets = new ArrayList<>();
        for (int t = 0; t < cancelThreads; t++) {
            long orderId = order("J" + t, set(10));
            targets.add(new long[]{orderId, itemOf(orderId)});
        }

        ExecutorService executor = Executors.newFixedThreadPool(cancelThreads + registerThreads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (long[] target : targets) {
            futures.add(executor.submit(() -> {
                login(ADMIN);
                start.await();
                for (int i = 0; i < cancelsPerThread; i++) {
                    cancellationService.cancel(target[0], List.of(new CancellationItemRequest(target[1], 1)), ADMIN);
                }
                return null;
            }));
        }
        for (int t = 0; t < registerThreads; t++) {
            int thread = t;
            futures.add(executor.submit(() -> {
                List<OrderRegistrationCommand> commands = new ArrayList<>();
                for (int i = 0; i < ordersPerRegister; i++) {
                    commands.add(command("K" + thread + "-" + i, set(1)));
                }
                start.await();
                return registrationService.registerAll(commands);
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        executor.shutdown();

        int expected = cancelThreads * (10 - cancelsPerThread) + registerThreads * ordersPerRegister;
        assertThat(allocated(toner)).isEqualTo(expected);
        assertThat(allocated(mask)).isEqualTo(expected);
        assertThat(allocationAdminService.checkConsistency().mismatches())
                .noneMatch(m -> List.of(toner, cream, mask).contains(m.productId()));
    }

    // ------------------------------------------------------------------ fixtures

    private CancellationResponse cancel(long orderId, long itemId, int quantity) {
        return cancellationService.cancel(orderId, List.of(new CancellationItemRequest(itemId, quantity)), ADMIN);
    }

    private Item set(int quantity) {
        return Item.ofSaleProduct(setProduct, "Q-SET", "", quantity, price(4500));
    }

    private long order(String key, Item... items) {
        List<OrderRegistrationResult> results = registrationService.registerAll(List.of(command(key, items)));
        assertThat(results.getFirst().status()).isEqualTo(OrderRegistrationResult.Status.REGISTERED);
        return jdbc.queryForObject("SELECT id FROM orders WHERE channel_order_no = ?", Long.class, suffix + "-" + key);
    }

    private OrderRegistrationCommand command(String key, Item... items) {
        return new OrderRegistrationCommand(channelId, suffix + "-" + key, brandId, price(10000), price(10000), "JPY",
                "주문자", "수취인", null, null, "주소", null, ORDERED_AT, List.of(items));
    }

    private long itemOf(long orderId) {
        return jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ? AND sale_product_id = ?",
                Long.class, orderId, setProduct);
    }

    private long giftItemOf(long orderId) {
        return jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ? AND item_type = 'GIFT_PRODUCT'",
                Long.class, orderId);
    }

    private long shipment(long orderId, int roundNo, String status) {
        return insert("""
                INSERT INTO shipments (order_id, brand_id, shipment_no, round_no, status, created_user_id)
                VALUES (?, ?, ?, ?, ?, 1)
                """, orderId, brandId, orderId + "-" + roundNo + "-" + suffix, roundNo, status);
    }

    private void assign(long shipmentId, long itemId, int quantity) {
        jdbc.update("INSERT INTO shipment_items (shipment_id, order_item_id, quantity, created_user_id) VALUES (?, ?, ?, 1)",
                shipmentId, itemId, quantity);
    }

    private int assigned(long shipmentId, long itemId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM shipment_items WHERE shipment_id = ? AND order_item_id = ?",
                Integer.class, shipmentId, itemId);
    }

    private int quantity(long itemId) {
        return jdbc.queryForObject("SELECT quantity FROM order_items WHERE id = ?", Integer.class, itemId);
    }

    private String status(long itemId) {
        return jdbc.queryForObject("SELECT status FROM order_items WHERE id = ?", String.class, itemId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private String shipmentStatus(long shipmentId) {
        return jdbc.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, shipmentId);
    }

    private List<String> orderHistory(long orderId) {
        return jdbc.queryForList("""
                SELECT CONCAT(COALESCE(previous_status, 'null'), '>', current_status) FROM order_status_history
                WHERE order_id = ? AND target_type = 'ORDER' ORDER BY id
                """, String.class, orderId);
    }

    private List<String> shipmentHistory(long shipmentId) {
        return jdbc.queryForList("""
                SELECT CONCAT(COALESCE(previous_status, 'null'), '>', current_status) FROM order_status_history
                WHERE target_type = 'SHIPMENT' AND target_id = ? ORDER BY id
                """, String.class, shipmentId);
    }

    private static BigDecimal price(int value) {
        return BigDecimal.valueOf(value);
    }
}
