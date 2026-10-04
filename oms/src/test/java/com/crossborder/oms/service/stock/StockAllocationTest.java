package com.crossborder.oms.service.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.infra.lock.DistributedLockManager;
import com.crossborder.oms.dto.product.ChannelMappingRequest;
import com.crossborder.oms.dto.stock.AllocationBackfillResponse;
import com.crossborder.oms.dto.stock.AllocationConsistencyResponse;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.order.OrderRegistrationCommand;
import com.crossborder.oms.service.order.OrderRegistrationCommand.Item;
import com.crossborder.oms.service.order.OrderRegistrationResult;
import com.crossborder.oms.service.order.OrderRegistrationService;
import com.crossborder.oms.service.product.ChannelMappingService;
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
 * 재고 할당: 실제 MariaDB + Redis(Redisson 락)로 등록 경로·매핑 완료·락 타임아웃·동시 등록·소급 할당을 검증한다.
 * <p>
 * 테스트마다 제품·판매상품을 새로 만들어(SKU 접미사) 서로의 allocated_stock에 영향을 주지 않는다.
 * <ul>
 *   <li>SET 판매상품 = 토너 x1 + 크림 x1 + 마스크 x1(구성 고정 사은품)</li>
 *   <li>MASK10 판매상품 = 마스크 x10</li>
 * </ul>
 */
class StockAllocationTest extends IntegrationTestBase {

    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, UserRole.ADMIN, null, null);
    private static final LocalDateTime ORDERED_AT = LocalDateTime.of(2026, 10, 1, 10, 0);

    @Autowired
    private OrderRegistrationService orderRegistrationService;
    @Autowired
    private ChannelMappingService channelMappingService;
    @Autowired
    private AllocationAdminService allocationAdminService;
    @Autowired
    private DistributedLockManager lockManager;

    private long channelId;
    private long brandId;
    private long toner;
    private long cream;
    private long mask;
    private long setProduct;
    private long mask10Product;

    @BeforeEach
    void setUp() {
        channelId = channelId("QOO10");
        brandId = brand(company("할당상사"), "할당브랜드");
        toner = product(brandId, "TONER");
        cream = product(brandId, "CREAM");
        mask = product(brandId, "MASK");
        setProduct = saleProduct(brandId, "SET");
        composition(setProduct, toner, 1, false);
        composition(setProduct, cream, 1, false);
        composition(setProduct, mask, 1, true);
        mask10Product = saleProduct(brandId, "MASK10");
        composition(mask10Product, mask, 10, false);
    }

    @Test
    void 등록하면_구성_전개_수량만큼_할당되고_할당_시각이_기록된다() {
        List<OrderRegistrationResult> results = orderRegistrationService.registerAll(List.of(
                order("A", Item.ofSaleProduct(setProduct, "Q-SET", "", 2, price(4500)),
                        Item.ofGift(mask, "MASK-SKU", "", 1, BigDecimal.ZERO)),
                order("B", Item.ofSaleProduct(mask10Product, "Q-MASK", "10P", 3, price(1800)))));

        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo(OrderRegistrationResult.Status.REGISTERED));
        assertThat(allocated(toner)).isEqualTo(2);
        assertThat(allocated(cream)).isEqualTo(2);
        // SET 구성 사은품 2 + 건별 사은품 1 + MASK10 x3 = 33
        assertThat(allocated(mask)).isEqualTo(33);
        assertThat(allocatedAt("A")).isNotNull();
        assertThat(allocatedAt("B")).isNotNull();
    }

    @Test
    void 매핑안됨_주문은_할당하지_않고_매핑이_완료되면_주문_전체를_할당한다() {
        orderRegistrationService.registerAll(List.of(order("P",
                Item.ofSaleProduct(null, "Q-NEW-" + suffix, "", 2, price(4500)),
                Item.ofSaleProduct(mask10Product, "Q-MASK", "10P", 1, price(1800)))));

        assertThat(allocatedAt("P")).isNull();
        assertThat(allocated(toner)).isZero();
        assertThat(allocated(mask)).isZero();

        channelMappingService.create(new ChannelMappingRequest(channelId, "Q-NEW-" + suffix, null, setProduct), ADMIN);

        assertThat(allocatedAt("P")).isNotNull();
        assertThat(allocated(toner)).isEqualTo(2);
        assertThat(allocated(cream)).isEqualTo(2);
        assertThat(allocated(mask)).isEqualTo(2 + 10);
    }

    @Test
    void 락을_얻지_못한_주문은_등록되지_않고_실패_사유가_남으며_같은_묶음의_다른_주문은_등록된다() throws Exception {
        long other = product(brandId, "OTHER");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        Future<?> holding = holder.submit(() -> lockManager.executeWithLocks(StockAllocator.lockKeys(List.of(toner)), () -> {
            locked.countDown();
            await(release);
            return null;
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        List<OrderRegistrationResult> results;
        try {
            results = orderRegistrationService.registerAll(List.of(
                    order("LOCKED", Item.ofSaleProduct(setProduct, "Q-SET", "", 1, price(4500))),
                    order("FREE", Item.ofGift(other, "OTHER-SKU", "", 1, BigDecimal.ZERO))));
        } finally {
            release.countDown();
            holding.get(10, TimeUnit.SECONDS);
            holder.shutdown();
        }

        assertThat(results.get(0).status()).isEqualTo(OrderRegistrationResult.Status.FAILED);
        assertThat(results.get(0).message()).contains("락");
        assertThat(results.get(1).status()).isEqualTo(OrderRegistrationResult.Status.REGISTERED);
        assertThat(orderCount("LOCKED")).isZero();
        assertThat(allocated(toner)).isZero();
        assertThat(allocated(other)).isEqualTo(1);
    }

    @Test
    void 동시_등록에서도_할당재고는_등록된_주문의_전개_합과_같다() throws Exception {
        int threads = 8;
        int ordersPerThread = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<OrderRegistrationResult>>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int thread = t;
            futures.add(executor.submit(() -> {
                List<OrderRegistrationCommand> commands = new ArrayList<>();
                for (int i = 0; i < ordersPerThread; i++) {
                    commands.add(order("C" + thread + "-" + i, Item.ofSaleProduct(setProduct, "Q-SET", "", 1, price(4500))));
                }
                start.await();
                return orderRegistrationService.registerAll(commands);
            }));
        }
        start.countDown();
        long registered = 0;
        for (Future<List<OrderRegistrationResult>> future : futures) {
            registered += future.get(60, TimeUnit.SECONDS).stream()
                    .filter(r -> r.status() == OrderRegistrationResult.Status.REGISTERED).count();
        }
        executor.shutdown();

        assertThat(registered).isEqualTo(threads * ordersPerThread);
        assertThat(allocated(toner)).isEqualTo(threads * ordersPerThread);
        assertThat(allocated(mask)).isEqualTo(threads * ordersPerThread);
    }

    @Test
    void 소급_할당은_미할당_주문만_한_번_할당하고_매핑안됨은_건너뛰며_정합_검증을_통과한다() {
        orderRegistrationService.registerAll(List.of(
                order("L1", Item.ofSaleProduct(setProduct, "Q-SET", "", 2, price(4500))),
                order("L2", Item.ofSaleProduct(mask10Product, "Q-MASK", "10P", 1, price(1800))),
                order("LP", Item.ofSaleProduct(null, "Q-PENDING-" + suffix, "", 1, price(1000))),
                order("LC", Item.ofSaleProduct(setProduct, "Q-SET", "", 5, price(4500))),
                order("LD", Item.ofSaleProduct(setProduct, "Q-SET", "", 7, price(4500)))));
        // LD: 미할당 상태로 배송 완료까지 간 이상 데이터
        jdbc.update("UPDATE orders SET allocated_at = NULL, status = 'DELIVERED' WHERE channel_order_no = ?", no("LD"));
        // allocated_at 도입 이전 주문처럼 만든다 (LC는 그 상태로 전량 취소된 주문)
        jdbc.update("UPDATE orders SET allocated_at = NULL WHERE channel_order_no IN (?, ?, ?)", no("L1"), no("L2"), no("LC"));
        jdbc.update("UPDATE orders SET status = 'CANCELED' WHERE channel_order_no = ?", no("LC"));
        jdbc.update("UPDATE order_items SET status = 'CANCELED' WHERE order_id = (SELECT id FROM orders WHERE channel_order_no = ?)",
                no("LC"));
        jdbc.update("UPDATE products SET allocated_stock = 0 WHERE id IN (?, ?, ?)", toner, cream, mask);
        // 미할당 주문은 불일치가 아니라 소급 대상으로 잡힌다 (기대값은 할당 완료 주문만 합산)
        assertThat(allocationAdminService.checkConsistency().unallocatedOrders()).isGreaterThanOrEqualTo(2);
        assertThat(mismatchesOfThisTest()).isEmpty();

        AllocationBackfillResponse first = allocationAdminService.backfill();
        assertThat(first.processed())
                .isEqualTo(first.allocated() + first.skipped() + first.failed() + first.anomalies());
        assertThat(first.anomalyOrderIds()).contains(orderId("LD"));
        assertThat(first.allocated()).isGreaterThanOrEqualTo(2);
        assertThat(first.skipped()).isGreaterThanOrEqualTo(1); // LP: 매핑안됨
        assertThat(first.failed()).isZero();
        assertThat(allocated(toner)).isEqualTo(2);
        assertThat(allocated(mask)).isEqualTo(2 + 10);
        assertThat(allocatedAt("L1")).isNotNull();
        assertThat(allocatedAt("LP")).isNull();
        assertThat(allocatedAt("LC")).isNull(); // 취소 주문은 소급 대상이 아니다
        assertThat(allocatedAt("LD")).isNull(); // DELIVERED는 할당하지 않는다 (토너 할당 2 = L1만)

        // 멱등: 다시 돌리면 남은 대상은 매핑안됨뿐이라 전부 건너뛴다
        AllocationBackfillResponse second = allocationAdminService.backfill();
        assertThat(second.allocated()).isZero();
        assertThat(second.skipped() + second.anomalies()).isEqualTo(second.processed());
        assertThat(allocated(mask)).isEqualTo(2 + 10);

        AllocationConsistencyResponse consistency = allocationAdminService.checkConsistency();
        assertThat(mismatchesOfThisTest()).isEmpty();
        assertThat(consistency.unallocatedOrders()).isZero();
        assertThat(consistency.mappingPendingOrders()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 항목이_하나씩_매핑될_때_마지막_항목이_확정되는_순간_주문_전체를_할당한다() {
        String codeA = "Q-A-" + suffix;
        String codeB = "Q-B-" + suffix;
        String codeC = "Q-C-" + suffix;
        orderRegistrationService.registerAll(List.of(order("ABC",
                Item.ofSaleProduct(null, codeA, "", 1, price(1000)),
                Item.ofSaleProduct(null, codeB, "", 1, price(1000)),
                Item.ofSaleProduct(null, codeC, "", 1, price(1000)))));

        channelMappingService.create(new ChannelMappingRequest(channelId, codeA, null, setProduct), ADMIN);
        assertThat(allocatedAt("ABC")).isNull();
        assertThat(allocated(toner)).isZero();

        channelMappingService.create(new ChannelMappingRequest(channelId, codeB, null, setProduct), ADMIN);
        assertThat(allocatedAt("ABC")).isNull();
        assertThat(allocated(toner)).isZero();

        channelMappingService.create(new ChannelMappingRequest(channelId, codeC, null, mask10Product), ADMIN);
        assertThat(allocatedAt("ABC")).isNotNull();
        assertThat(allocated(toner)).isEqualTo(2);
        assertThat(allocated(cream)).isEqualTo(2);
        assertThat(allocated(mask)).isEqualTo(2 + 10);
    }

    private List<AllocationConsistencyResponse.Mismatch> mismatchesOfThisTest() {
        return allocationAdminService.checkConsistency().mismatches().stream()
                .filter(m -> List.of(toner, cream, mask).contains(m.productId()))
                .toList();
    }

    private OrderRegistrationCommand order(String key, Item... items) {
        return new OrderRegistrationCommand(channelId, no(key), brandId, price(10000), price(10000), "JPY",
                "주문자", "수취인", null, null, "주소", null, ORDERED_AT, List.of(items));
    }

    private String no(String key) {
        return suffix + "-" + key;
    }

    private LocalDateTime allocatedAt(String key) {
        return jdbc.queryForObject("SELECT allocated_at FROM orders WHERE channel_order_no = ?",
                LocalDateTime.class, no(key));
    }

    private long orderId(String key) {
        return jdbc.queryForObject("SELECT id FROM orders WHERE channel_order_no = ?", Long.class, no(key));
    }

    private int orderCount(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE channel_order_no = ?", Integer.class, no(key));
    }

    private static BigDecimal price(int value) {
        return BigDecimal.valueOf(value);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
