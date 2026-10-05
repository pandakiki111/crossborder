package com.crossborder.oms.service.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crossborder.common.entity.order.OrderStatus;
import com.crossborder.common.entity.order.ShipmentStatus;
import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.oms.dto.CappedPageResponse;
import com.crossborder.oms.dto.order.OrderSearchCondition;
import com.crossborder.oms.dto.order.OrderSearchCondition.SkuMatch;
import com.crossborder.oms.dto.order.OrderSummaryResponse;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.repository.OrderQueryRepository;
import com.crossborder.oms.repository.OrderSearchCriteria;
import com.crossborder.oms.repository.OrderSearchCriteria.SkuFilter;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.order.OrderRegistrationCommand.Item;
import com.crossborder.oms.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * 주문 목록 검색: 확정 스펙의 필터, 쿼리 형태 대체(상관 count ↔ EXISTS)의 결과 동치, 제약 검증(400).
 * <p>
 * 테스트마다 회사를 새로 만들고 COMPANY_STAFF로 조회해 같은 DB의 다른 테스트 데이터와 섞이지 않게 한다.
 */
class OrderSearchTest extends IntegrationTestBase {

    private static final LocalDate DAY = LocalDate.now().minusDays(3);
    private static final LocalDateTime AT = DAY.atTime(10, 0);

    @Autowired
    private OrderQueryService orderQueryService;
    @Autowired
    private OrderQueryRepository orderQueryRepository;
    @Autowired
    private OrderRegistrationService registrationService;

    private long qoo10;
    private long rakuten;
    private long companyId;
    private long brandA;
    private long brandB;
    private long toner;
    private long cream;
    private long tonerSet;
    private long creamSet;
    private AuthenticatedUser staff;

    @BeforeEach
    void setUp() {
        qoo10 = channelId("QOO10");
        rakuten = channelId("RAKUTEN");
        companyId = company("검색상사");
        brandA = brand(companyId, "검색A");
        brandB = brand(companyId, "검색B");
        toner = product(brandA, "SRCH-TONER");
        cream = product(brandA, "SRCH-CREAM");
        tonerSet = saleProduct(brandA, "TONER-SET");
        composition(tonerSet, toner, 1, false);
        creamSet = saleProduct(brandA, "CREAM-SET");
        composition(creamSet, cream, 1, false);
        staff = new AuthenticatedUser(1L, UserRole.COMPANY_STAFF, companyId, null);
    }

    // ------------------------------------------------------------------ 기본 목록

    @Test
    void 채널_코드를_붙이고_최신순으로_내리며_기간_미지정이면_최근_30일만_본다() {
        long old = order("OLD", qoo10, brandA, LocalDateTime.now().minusDays(40), sale(tonerSet));
        long first = order("FIRST", qoo10, brandA, AT, sale(tonerSet), unmapped());
        long second = order("SECOND", rakuten, brandA, AT.plusHours(1), sale(tonerSet));

        CappedPageResponse<OrderSummaryResponse> page = search(OrderSearchCondition.empty());

        assertThat(page.content()).extracting(OrderSummaryResponse::orderId).containsExactly(second, first);
        assertThat(page.content()).extracting(OrderSummaryResponse::salesChannelCode).containsExactly("RAKUTEN", "QOO10");
        OrderSummaryResponse pending = page.content().get(1);
        assertThat(pending.itemCount()).isEqualTo(2);
        assertThat(pending.unmappedItemCount()).isEqualTo(1);
        assertThat(pending.mappingPending()).isTrue();
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalCapped()).isFalse();
        assertThat(ids(withPeriod(DAY.minusDays(60), DAY))).contains(old);
    }

    @Test
    void 페이지가_가득_차면_건수를_따로_센다() {
        List<Long> orders = IntStream.range(0, 5).mapToObj(i -> order("P" + i, qoo10, brandA, AT.plusMinutes(i), sale(tonerSet)))
                .toList();

        CappedPageResponse<OrderSummaryResponse> first = orderQueryService.search(OrderSearchCondition.empty(),
                PageRequest.of(0, 2), staff);
        CappedPageResponse<OrderSummaryResponse> last = orderQueryService.search(OrderSearchCondition.empty(),
                PageRequest.of(2, 2), staff);

        assertThat(first.content()).extracting(OrderSummaryResponse::orderId).containsExactly(orders.get(4), orders.get(3));
        assertThat(first.totalElements()).isEqualTo(5);
        assertThat(last.content()).extracting(OrderSummaryResponse::orderId).containsExactly(orders.get(0));
        assertThat(last.totalElements()).isEqualTo(5);
    }

    @Test
    void 건수는_상한까지만_센다() {
        IntStream.range(0, 4).forEach(i -> order("C" + i, qoo10, brandA, AT, sale(tonerSet)));

        assertThat(orderQueryRepository.countUpTo(criteria(), staff, 3)).isEqualTo(3);
        assertThat(orderQueryRepository.countUpTo(criteria(), staff, 10)).isEqualTo(4);
    }

    // ------------------------------------------------------------------ 필터

    @Test
    void 상태는_다중_선택이고_매핑안됨과_채널로_좁힌다() {
        long paid = order("PAID", qoo10, brandA, AT, sale(tonerSet));
        long shipping = order("SHIPPING", rakuten, brandA, AT, sale(tonerSet));
        long delivered = order("DELIVERED", qoo10, brandA, AT, sale(tonerSet));
        long pending = order("PENDING", qoo10, brandA, AT, unmapped());
        setStatus(shipping, OrderStatus.SHIPPING);
        setStatus(delivered, OrderStatus.DELIVERED);

        assertThat(ids(condition().status(List.of(OrderStatus.PAID, OrderStatus.SHIPPING)).build()))
                .containsExactlyInAnyOrder(paid, shipping, pending);
        assertThat(ids(condition().mappingPending(true).build())).containsExactly(pending);
        assertThat(ids(condition().salesChannelId(rakuten).build())).containsExactly(shipping);
    }

    @Test
    void 브랜드_선택은_항목_브랜드_기준이고_BRAND_STAFF는_자기_브랜드로_고정된다() {
        long a = order("A", qoo10, brandA, AT, sale(tonerSet));
        long b = order("B", qoo10, brandB, AT, unmapped());

        assertThat(ids(condition().brandId(brandB).build())).containsExactly(b);
        assertThat(orderQueryService.search(condition().brandId(brandB).build(), PageRequest.of(0, 1), staff)
                .totalElements()).as("건수(EXISTS 형태)도 같은 주문").isEqualTo(1);

        AuthenticatedUser brandStaff = new AuthenticatedUser(1L, UserRole.BRAND_STAFF, companyId, brandA);
        assertThat(orderQueryService.search(condition().brandId(brandB).build(), PageRequest.of(0, 20), brandStaff)
                .content()).extracting(OrderSummaryResponse::orderId).containsExactly(a);
    }

    /**
     * 배송상태는 EXISTS / NOT EXISTS 대신 상관 count 형태로 구현했다 (README "대용량 조회 검증").
     * 경계 케이스에서 EXISTS 원형과 결과가 같은지 고정한다. 미분리 = 유효 회차(CANCELED 아님) 없음.
     */
    @Test
    void 배송상태_필터는_EXISTS와_결과가_같고_CANCELED_회차만_남은_주문은_미분리다() {
        long none = order("NONE", qoo10, brandA, AT, sale(tonerSet));
        long canceledOnly = order("CANCELED-ONLY", qoo10, brandA, AT, sale(tonerSet));
        shipment(canceledOnly, 1, "CANCELED");
        long resplit = order("RESPLIT", qoo10, brandA, AT, sale(tonerSet));
        shipment(resplit, 1, "CANCELED");
        shipment(resplit, 2, "CREATED");
        long created = order("CREATED", qoo10, brandA, AT, sale(tonerSet));
        shipment(created, 1, "CREATED");
        long shipped = order("SHIPPED", qoo10, brandA, AT, sale(tonerSet));
        shipment(shipped, 1, "MASTER_SHIPPED");
        shipment(shipped, 2, "INSTRUCTED");

        assertThat(ids(condition().unsplit(true).build())).containsExactlyInAnyOrder(none, canceledOnly)
                .isEqualTo(nativeExists(null, true));
        assertThat(ids(condition().shipmentStatus(List.of(ShipmentStatus.CREATED)).build()))
                .containsExactlyInAnyOrder(resplit, created)
                .isEqualTo(nativeExists(List.of("CREATED"), false));
        assertThat(ids(condition().shipmentStatus(List.of(ShipmentStatus.CANCELED)).build()))
                .containsExactlyInAnyOrder(canceledOnly, resplit)
                .isEqualTo(nativeExists(List.of("CANCELED"), false));
        assertThat(ids(condition().shipmentStatus(List.of(ShipmentStatus.INSTRUCTED, ShipmentStatus.MASTER_SHIPPED)).build()))
                .containsExactly(shipped)
                .isEqualTo(nativeExists(List.of("INSTRUCTED", "MASTER_SHIPPED"), false));
        assertThat(ids(condition().shipmentStatus(List.of(ShipmentStatus.CREATED)).unsplit(true).build()))
                .containsExactlyInAnyOrder(none, canceledOnly, resplit, created)
                .isEqualTo(nativeExists(List.of("CREATED"), true));
    }

    @Test
    void 주문번호는_복수_정확_일치와_단건_부분_일치를_지원한다() {
        long one = order("NO-1001", qoo10, brandA, AT, sale(tonerSet));
        long two = order("NO-2002", qoo10, brandA, AT, sale(tonerSet));
        order("NO-3003", qoo10, brandA, AT, sale(tonerSet));

        List<String> pasted = List.of(suffix + "-NO-1001", " " + suffix + "-NO-2002 ", "", suffix + "-NO-1001");
        assertThat(ids(condition().channelOrderNos(pasted).build())).containsExactlyInAnyOrder(one, two);
        assertThat(ids(condition().channelOrderNoContains("2002").build())).containsExactly(two);
    }

    @Test
    void SKU는_판매상품_구성과_건별_사은품으로_찾고_부분_일치도_된다() {
        long tonerOrder = order("SKU-T", qoo10, brandA, AT, sale(tonerSet));
        long creamOrder = order("SKU-C", qoo10, brandA, AT, sale(creamSet));
        long giftOrder = order("SKU-G", qoo10, brandA, AT, sale(creamSet), gift(toner));

        String tonerSku = "SRCH-TONER-" + suffix;
        assertThat(ids(condition().sku(tonerSku).build())).containsExactlyInAnyOrder(tonerOrder, giftOrder);
        assertThat(ids(condition().sku("SRCH-").skuMatch(SkuMatch.PARTIAL).build()))
                .containsExactlyInAnyOrder(tonerOrder, creamOrder, giftOrder);
        assertThat(ids(condition().sku("NO-SUCH-SKU").build())).isEmpty();
    }

    /** SKU 판정은 경로만 바꾼다 — 희소(IN 세미조인)와 밀집(프로브)의 결과가 같아야 한다 */
    @Test
    void SKU_희소_경로와_밀집_경로의_결과가_같다() {
        long tonerOrder = order("PATH-T", qoo10, brandA, AT, sale(tonerSet));
        order("PATH-C", qoo10, brandA, AT, sale(creamSet));
        long giftOrder = order("PATH-G", qoo10, brandA, AT, sale(creamSet), gift(toner));

        SkuFilter resolved = orderQueryRepository.resolveSku("SRCH-TONER-" + suffix, false, 5000);
        assertThat(resolved.dense()).isFalse();
        SkuFilter dense = new SkuFilter(resolved.saleProductIds(), resolved.productIds(), true);
        assertThat(orderQueryRepository.resolveSku("SRCH-TONER-" + suffix, false, 1).dense())
                .as("항목 2건 > 임계 1").isTrue();

        assertThat(rowIds(criteriaWithSku(resolved))).containsExactlyInAnyOrder(tonerOrder, giftOrder)
                .isEqualTo(rowIds(criteriaWithSku(dense)));
        assertThat(orderQueryRepository.countUpTo(criteriaWithSku(resolved), staff, 100))
                .isEqualTo(orderQueryRepository.countUpTo(criteriaWithSku(dense), staff, 100)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 제약 (400)

    @Test
    void 페이지_크기와_offset_상한을_넘으면_400() {
        assertThatThrownBy(() -> orderQueryService.search(OrderSearchCondition.empty(), PageRequest.of(0, 1001), staff))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("1,000");
        assertThatThrownBy(() -> orderQueryService.search(OrderSearchCondition.empty(), PageRequest.of(100_001, 1), staff))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("100,000");
        assertThat(orderQueryService.search(OrderSearchCondition.empty(), PageRequest.of(100, 1000), staff).content())
                .as("offset 100,000은 허용").isEmpty();
    }

    @Test
    void 기간_역전과_최대_기간_초과는_400() {
        assertThatThrownBy(() -> search(withPeriod(DAY, DAY.minusDays(1))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("늦습니다");
        assertThatThrownBy(() -> search(withPeriod(DAY.minusDays(366), DAY)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("366일");
        assertThat(search(withPeriod(DAY.minusDays(365), DAY)).content()).isNotNull();
    }

    @Test
    void 부분_일치는_31일을_넘는_기간이면_400() {
        LocalDate from = DAY.minusDays(31);
        assertThatThrownBy(() -> search(condition().orderedFrom(from).orderedTo(DAY).channelOrderNoContains("1").build()))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("31일");
        assertThatThrownBy(() -> search(condition().orderedFrom(from).orderedTo(DAY).sku("SRCH").skuMatch(SkuMatch.PARTIAL)
                .build()))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("31일");
        // 정확 일치는 일반 최대 기간만 적용
        assertThat(search(condition().orderedFrom(from).orderedTo(DAY).sku("SRCH").build()).content()).isEmpty();
    }

    @Test
    void 주문번호는_501개부터_400() {
        List<String> nos = IntStream.rangeClosed(1, 501).mapToObj(i -> "N" + i).toList();
        assertThatThrownBy(() -> search(condition().channelOrderNos(nos).build()))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("500개");
        assertThat(search(condition().channelOrderNos(nos.subList(0, 500)).build()).content()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private CappedPageResponse<OrderSummaryResponse> search(OrderSearchCondition condition) {
        return orderQueryService.search(condition, PageRequest.of(0, 100), staff);
    }

    private Set<Long> ids(OrderSearchCondition condition) {
        return search(condition).content().stream().map(OrderSummaryResponse::orderId).collect(Collectors.toSet());
    }

    private Set<Long> rowIds(OrderSearchCriteria criteria) {
        return orderQueryRepository.findPage(criteria, staff, 0, 100).stream()
                .map(OrderQueryRepository.Row::orderId).collect(Collectors.toSet());
    }

    /** EXISTS / NOT EXISTS 원형 (스펙 문구 그대로). unsplit이면 "유효 회차 없음"과 OR */
    private Set<Long> nativeExists(List<String> statuses, boolean unsplit) {
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        args.add(companyId);
        if (statuses != null) {
            conditions.add("EXISTS (SELECT 1 FROM shipments s WHERE s.order_id = o.id AND s.status IN ("
                    + String.join(",", Collections.nCopies(statuses.size(), "?")) + "))");
            args.addAll(statuses);
        }
        if (unsplit) {
            conditions.add("NOT EXISTS (SELECT 1 FROM shipments s WHERE s.order_id = o.id AND s.status <> 'CANCELED')");
        }
        return Set.copyOf(jdbc.queryForList("SELECT o.id FROM orders o WHERE o.company_id = ? AND ("
                + String.join(" OR ", conditions) + ")", Long.class, args.toArray()));
    }

    private OrderSearchCriteria criteria() {
        return criteriaWithSku(null);
    }

    private OrderSearchCriteria criteriaWithSku(SkuFilter sku) {
        return new OrderSearchCriteria(DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay(), List.of(), null, null, null,
                List.of(), false, null, List.of(), null, sku);
    }

    private static OrderSearchCondition withPeriod(LocalDate from, LocalDate to) {
        return condition().orderedFrom(from).orderedTo(to).build();
    }

    private static ConditionBuilder condition() {
        return new ConditionBuilder();
    }

    private long order(String key, long channelId, long brandId, LocalDateTime orderedAt, Item... items) {
        registrationService.registerAll(List.of(new OrderRegistrationCommand(channelId, suffix + "-" + key, brandId,
                price(), price(), "JPY", "주문자", "수취인", null, null, "주소", null, orderedAt, List.of(items))));
        return jdbc.queryForObject("SELECT id FROM orders WHERE channel_order_no = ?", Long.class, suffix + "-" + key);
    }

    private static Item sale(long saleProductId) {
        return Item.ofSaleProduct(saleProductId, "CODE", "", 1, price());
    }

    private static Item unmapped() {
        return Item.ofSaleProduct(null, "UNKNOWN", "", 1, price());
    }

    private static Item gift(long productId) {
        return Item.ofGift(productId, "GIFT", "", 1, BigDecimal.ZERO);
    }

    private void setStatus(long orderId, OrderStatus status) {
        jdbc.update("UPDATE orders SET status = ? WHERE id = ?", status.name(), orderId);
    }

    private void shipment(long orderId, int roundNo, String status) {
        jdbc.update("""
                INSERT INTO shipments (order_id, brand_id, shipment_no, round_no, status, created_user_id)
                VALUES (?, ?, ?, ?, ?, 1)
                """, orderId, brandA, orderId + "-" + roundNo + "-" + suffix, roundNo, status);
    }

    private static BigDecimal price() {
        return BigDecimal.valueOf(1000);
    }

    /** 테스트 가독성용 조건 빌더 (기간은 DAY 하루가 기본) */
    private static final class ConditionBuilder {
        private List<OrderStatus> status;
        private Boolean mappingPending;
        private Long salesChannelId;
        private Long brandId;
        private List<ShipmentStatus> shipmentStatus;
        private Boolean unsplit;
        private List<String> channelOrderNos;
        private String channelOrderNoContains;
        private String sku;
        private SkuMatch skuMatch;
        private LocalDate orderedFrom = DAY;
        private LocalDate orderedTo = DAY;

        ConditionBuilder status(List<OrderStatus> v) { status = v; return this; }
        ConditionBuilder mappingPending(Boolean v) { mappingPending = v; return this; }
        ConditionBuilder salesChannelId(Long v) { salesChannelId = v; return this; }
        ConditionBuilder brandId(Long v) { brandId = v; return this; }
        ConditionBuilder shipmentStatus(List<ShipmentStatus> v) { shipmentStatus = v; return this; }
        ConditionBuilder unsplit(Boolean v) { unsplit = v; return this; }
        ConditionBuilder channelOrderNos(List<String> v) { channelOrderNos = v; return this; }
        ConditionBuilder channelOrderNoContains(String v) { channelOrderNoContains = v; return this; }
        ConditionBuilder sku(String v) { sku = v; return this; }
        ConditionBuilder skuMatch(SkuMatch v) { skuMatch = v; return this; }
        ConditionBuilder orderedFrom(LocalDate v) { orderedFrom = v; return this; }
        ConditionBuilder orderedTo(LocalDate v) { orderedTo = v; return this; }

        OrderSearchCondition build() {
            return new OrderSearchCondition(status, mappingPending, salesChannelId, brandId, shipmentStatus, unsplit,
                    null, channelOrderNos, channelOrderNoContains, sku, skuMatch, orderedFrom, orderedTo);
        }
    }
}
