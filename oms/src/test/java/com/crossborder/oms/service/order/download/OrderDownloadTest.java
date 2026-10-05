package com.crossborder.oms.service.order.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crossborder.common.entity.gift.GiftConditionMode;
import com.crossborder.common.entity.gift.GiftGrantType;
import com.crossborder.common.entity.gift.GiftQuantityMode;
import com.crossborder.common.entity.gift.GiftTimeBasis;
import com.crossborder.common.entity.order.OrderStatus;
import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.oms.dto.gift.GiftEventRequest;
import com.crossborder.oms.dto.order.GiftAddRequest;
import com.crossborder.oms.dto.order.OrderDownloadEstimate;
import com.crossborder.oms.dto.order.OrderSearchCondition.SkuMatch;
import com.crossborder.oms.dto.order.OrderSearchCondition;
import com.crossborder.oms.dto.order.OrderSummaryResponse;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.gift.GiftEventApplier;
import com.crossborder.oms.service.gift.GiftEventService;
import com.crossborder.oms.service.order.OrderGiftService;
import com.crossborder.oms.service.order.OrderQueryService;
import com.crossborder.oms.service.order.OrderRegistrationCommand.Item;
import com.crossborder.oms.service.order.OrderRegistrationCommand;
import com.crossborder.oms.service.order.OrderRegistrationService;
import com.crossborder.oms.support.IntegrationTestBase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

/**
 * 주문 다운로드: 목록과 같은 주문 집합, 제품 전개 행, 열 선택, 기간·상한 400, 사용자당 동시 1건(실제 Redis), 스코프.
 * <p>
 * 주문 수 상한은 5로 낮춰 상한 동작을 확인한다. 테스트마다 회사를 새로 만들고 COMPANY_STAFF로 받는다.
 */
@TestPropertySource(properties = "crossborder.order-download.max-orders=5")
class OrderDownloadTest extends IntegrationTestBase {

    private static final LocalDate DAY = LocalDate.now().minusDays(2);
    private static final LocalDateTime AT = DAY.atTime(9, 0);

    @Autowired
    private OrderDownloadService downloadService;
    @Autowired
    private OrderQueryService queryService;
    @Autowired
    private OrderRegistrationService registrationService;
    @Autowired
    private GiftEventService giftEventService;
    @Autowired
    private GiftEventApplier giftEventApplier;
    @Autowired
    private OrderGiftService orderGiftService;

    private long qoo10;
    private long companyId;
    private long brandA;
    private long brandB;
    private long toner;
    private long mask;
    private long tonerSet;
    private AuthenticatedUser staff;

    @BeforeEach
    void setUp() {
        qoo10 = channelId("QOO10");
        companyId = company("다운상사");
        brandA = brand(companyId, "다운A");
        brandB = brand(companyId, "다운B");
        toner = product(brandA, "DL-TONER");
        mask = product(brandA, "DL-MASK");
        tonerSet = saleProduct(brandA, "DL-SET");
        composition(tonerSet, toner, 1, false);
        composition(tonerSet, mask, 1, true);
        staff = new AuthenticatedUser(1000L + Math.abs(suffix.hashCode() % 100_000), UserRole.COMPANY_STAFF, companyId, null);
    }

    // ------------------------------------------------------------------ 주문 집합 · 행

    @Test
    void 같은_조건이면_주문_목록과_같은_주문_집합을_내린다() {
        order("SAME-1", brandA, AT, sale(tonerSet, 1));
        long shipping = order("SAME-2", brandA, AT.plusHours(1), sale(tonerSet, 1));
        order("SAME-3", brandB, AT.plusHours(2), unmapped());
        order("OUT-OF-RANGE", brandA, AT.minusDays(5), sale(tonerSet, 1));
        jdbc.update("UPDATE orders SET status = 'SHIPPING' WHERE id = ?", shipping);

        for (OrderSearchCondition condition : List.of(
                condition(null, null, null, null),
                condition(List.of(OrderStatus.PAID), null, null, null),
                condition(null, brandA, null, null),
                condition(null, null, "DL-TONER-" + suffix, SkuMatch.EXACT),
                condition(null, null, "DL-", SkuMatch.PARTIAL))) {
            Set<String> listed = queryService.search(condition, PageRequest.of(0, 100), staff).content().stream()
                    .map(OrderSummaryResponse::orderNo).collect(Collectors.toSet());
            assertThat(orderNos(download(condition, null))).as("조건 %s", condition).isEqualTo(listed);
        }
    }

    @Test
    void 항목을_제품_단위로_전개하고_취소_항목은_뺀다() {
        long orderId = order("EXPAND", brandA, AT, sale(tonerSet, 2), gift(mask, 1), unmapped(), sale(tonerSet, 1));
        long canceledItem = jdbc.queryForObject(
                "SELECT MAX(id) FROM order_items WHERE order_id = ? AND sale_product_id = ?", Long.class, orderId, tonerSet);
        jdbc.update("UPDATE order_items SET status = 'CANCELED' WHERE id = ?", canceledItem);

        Downloaded file = download(condition(null, null, null, null),
                List.of("SALE_PRODUCT_CODE", "CHANNEL_PRODUCT_CODE", "ITEM_QUANTITY", "SKU", "PRODUCT_QUANTITY", "GIFT",
                        "GIFT_SOURCE"));

        assertThat(file.rowCount()).isEqualTo(4);
        String set = "DL-SET-" + suffix;
        assertThat(file.rows()).containsExactly(
                List.of(file.orderNo(0), "QOO10", set, "CODE", "2", "DL-TONER-" + suffix, "2", "N", ""),
                List.of(file.orderNo(0), "QOO10", set, "CODE", "2", "DL-MASK-" + suffix, "2", "Y", "COMPOSITION"),
                List.of(file.orderNo(0), "QOO10", "", "GIFT", "1", "DL-MASK-" + suffix, "1", "Y", "COLLECTED"),
                List.of(file.orderNo(0), "QOO10", "", "UNKNOWN", "1", "", "", "N", ""));
    }

    @Test
    void 사은품출처는_구성_수신_이벤트_수동을_구분하고_이벤트_행엔_코드와_이벤트명이_나온다() {
        AuthenticatedUser admin = new AuthenticatedUser(1L, UserRole.ADMIN, null, null);
        login(admin);
        long orderId = order("SOURCE", brandA, AT, sale(tonerSet, 1), gift(mask, 1));
        giftEventService.create(new GiftEventRequest(brandA, "가을 증정-" + suffix, GiftTimeBasis.ORDERED,
                DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay(), null, null, GiftConditionMode.ALL,
                GiftGrantType.ALWAYS, GiftQuantityMode.FIXED, 1, null, null, null, List.of(),
                List.of(new GiftEventRequest.Item(toner, null, null))), admin);
        giftEventApplier.apply(List.of(orderId));
        orderGiftService.addGift(orderId, new GiftAddRequest(mask, 2), admin);

        Downloaded file = download(condition(null, null, null, null), List.of("SKU", "GIFT_SOURCE", "GIFT_EVENT"));
        String code = jdbc.queryForObject("SELECT code FROM gift_events WHERE name = ?", String.class, "가을 증정-" + suffix);

        assertThat(file.rows()).extracting(row -> row.subList(2, 5)).containsExactly(
                List.of("DL-TONER-" + suffix, "", ""),
                List.of("DL-MASK-" + suffix, "COMPOSITION", ""),
                List.of("DL-MASK-" + suffix, "COLLECTED", ""),
                List.of("DL-TONER-" + suffix, "EVENT", "[" + code + "] 가을 증정-" + suffix),
                List.of("DL-MASK-" + suffix, "MANUAL", ""));
    }

    @Test
    void 열은_요청_순서대로이고_주문번호와_채널은_항상_포함된다() {
        order("COL", brandA, AT, sale(tonerSet, 1));

        assertThat(download(condition(null, null, null, null), List.of("SKU", "ORDERED_AT")).header())
                .containsExactly("주문번호", "채널", "SKU", "주문일시");
        assertThat(download(condition(null, null, null, null), List.of("CHANNEL", "SKU", "ORDER_NO")).header())
                .containsExactly("채널", "SKU", "주문번호");
        assertThat(download(condition(null, null, null, null), null).header())
                .hasSize(OrderDownloadColumn.values().length);
        assertThatThrownBy(() -> download(condition(null, null, null, null), List.of("SKU", "NOPE")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("NOPE");
    }

    @Test
    void 파일명과_행_수를_응답에_싣는다() {
        order("NAME", brandA, AT, sale(tonerSet, 1));

        Downloaded file = download(condition(null, null, null, null), null);

        String day = DAY.toString().replace("-", "");
        assertThat(file.fileName()).startsWith("orders-" + day + "-" + day + "-").endsWith(".xlsx");
        assertThat(file.reportedRows()).isEqualTo(2).isEqualTo(file.rowCount());
    }

    // ------------------------------------------------------------------ 제약

    @Test
    void 기간_누락과_31일_초과는_400() {
        OrderSearchCondition noPeriod = OrderSearchCondition.empty();
        assertThatThrownBy(() -> download(noPeriod, null))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("최대 31일");
        OrderSearchCondition days32 = new OrderSearchCondition(null, null, null, null, null, null, null, null, null,
                null, null, DAY.minusDays(31), DAY);
        assertThatThrownBy(() -> download(days32, null))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("최대 31일");
        assertThatThrownBy(() -> downloadService.estimate(noPeriod, staff)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void 주문_수_상한을_넘으면_estimate가_알리고_다운로드는_400() {
        IntStream.range(0, 6).forEach(i -> order("CAP" + i, brandA, AT, sale(tonerSet, 1)));

        OrderDownloadEstimate estimate = downloadService.estimate(condition(null, null, null, null), staff);
        assertThat(estimate.exceedsLimit()).isTrue();
        assertThat(estimate.orderCount()).isEqualTo(5);
        assertThatThrownBy(() -> download(condition(null, null, null, null), null))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("5건");

        assertThat(downloadService.estimate(condition(null, brandB, null, null), staff))
                .isEqualTo(new OrderDownloadEstimate(0, false, 5));
    }

    @Test
    void 같은_사용자의_동시_다운로드는_409이고_다른_사용자는_막지_않는다() throws Exception {
        order("LOCK", brandA, AT, sale(tonerSet, 1));
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(() -> downloadService.download(condition(null, null, null, null), null,
                    staff, (fileName, rows) -> {
                        opened.countDown();
                        await(release);
                        return new ByteArrayOutputStream();
                    }));
            assertThat(opened.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> download(condition(null, null, null, null), null))
                    .isInstanceOf(ConflictException.class).hasMessageContaining("진행 중");
            AuthenticatedUser other = new AuthenticatedUser(staff.userId() + 1, UserRole.COMPANY_STAFF, companyId, null);
            assertThat(download(condition(null, null, null, null), null, other).rowCount()).isEqualTo(2);

            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThat(download(condition(null, null, null, null), null).rowCount()).as("끝나면 다시 받을 수 있다").isEqualTo(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void BRAND_STAFF는_자기_브랜드_항목이_있는_주문만_받는다() {
        long a = order("SCOPE-A", brandA, AT, sale(tonerSet, 1));
        order("SCOPE-B", brandB, AT, unmapped());
        AuthenticatedUser brandStaff = new AuthenticatedUser(staff.userId(), UserRole.BRAND_STAFF, companyId, brandA);

        String orderNoA = jdbc.queryForObject("SELECT order_no FROM orders WHERE id = ?", String.class, a);
        assertThat(orderNos(download(condition(null, brandB, null, null), null, brandStaff)))
                .as("브랜드 선택은 무시").containsExactly(orderNoA);
    }

    // ------------------------------------------------------------------ helpers

    private record Downloaded(String fileName, int reportedRows, List<String> header, List<List<String>> rows) {
        int rowCount() {
            return rows.size();
        }

        String orderNo(int row) {
            return rows.get(row).getFirst();
        }
    }

    private Downloaded download(OrderSearchCondition condition, List<String> columns) {
        return download(condition, columns, staff);
    }

    private Downloaded download(OrderSearchCondition condition, List<String> columns, AuthenticatedUser user) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String[] fileName = new String[1];
        int[] rows = new int[1];
        downloadService.download(condition, columns, user, (name, count) -> {
            fileName[0] = name;
            rows[0] = count;
            return out;
        });
        return parse(fileName[0], rows[0], out.toByteArray());
    }

    private static Downloaded parse(String fileName, int reportedRows, byte[] bytes) {
        DataFormatter formatter = new DataFormatter();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            int width = headerRow.getLastCellNum();
            List<String> header = new ArrayList<>();
            headerRow.forEach(cell -> header.add(cell.getStringCellValue()));
            List<List<String>> rows = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> values = new ArrayList<>();
                for (int c = 0; c < width; c++) {
                    Cell cell = row.getCell(c);
                    values.add(cell == null ? "" : formatter.formatCellValue(cell));
                }
                rows.add(values);
            }
            return new Downloaded(fileName, reportedRows, header, rows);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> orderNos(Downloaded file) {
        return file.rows().stream().map(List::getFirst).collect(Collectors.toSet());
    }

    private static OrderSearchCondition condition(List<OrderStatus> status, Long brandId, String sku, SkuMatch skuMatch) {
        return new OrderSearchCondition(status, null, null, brandId, null, null, null, null, null, sku, skuMatch,
                DAY, DAY);
    }

    private long order(String key, long brandId, LocalDateTime orderedAt, Item... items) {
        registrationService.registerAll(List.of(new OrderRegistrationCommand(qoo10, suffix + "-" + key, brandId,
                price(1000), price(1000), "JPY", "주문자", "수취인", "010", "12345", "주소", "문앞", orderedAt,
                List.of(items))));
        return jdbc.queryForObject("SELECT id FROM orders WHERE channel_order_no = ?", Long.class, suffix + "-" + key);
    }

    private static Item sale(long saleProductId, int quantity) {
        return Item.ofSaleProduct(saleProductId, "CODE", "", quantity, price(4500));
    }

    private static Item unmapped() {
        return Item.ofSaleProduct(null, "UNKNOWN", "", 1, price(1000));
    }

    private static Item gift(long productId, int quantity) {
        return Item.ofGift(productId, "GIFT", "", quantity, BigDecimal.ZERO);
    }

    private static BigDecimal price(int value) {
        return BigDecimal.valueOf(value);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
