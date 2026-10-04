package com.crossborder.oms.service.order.seed;

import com.crossborder.common.entity.organization.Brand;
import com.crossborder.common.entity.product.SaleProductChannelMapping;
import com.crossborder.common.entity.product.SalesChannel;
import com.crossborder.oms.exception.ForbiddenException;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.oms.repository.BrandRepository;
import com.crossborder.oms.repository.ProductRepository;
import com.crossborder.oms.repository.SalesChannelRepository;
import com.crossborder.oms.security.scope.ScopeCheck;
import com.crossborder.oms.security.scope.ScopeId;
import com.crossborder.oms.security.scope.ScopeTarget;
import com.crossborder.oms.service.order.OrderRegistrationCommand;
import com.crossborder.oms.service.order.OrderRegistrationResult;
import com.crossborder.oms.service.order.OrderRegistrationService;
import com.crossborder.oms.service.order.seed.OrderSeedSheet.ResultKind;
import com.crossborder.oms.service.product.ChannelProductResolver;
import com.crossborder.oms.service.product.ChannelProductResolver.MappingHistory;
import com.crossborder.oms.service.product.ChannelProductResolver.MappingKey;
import com.crossborder.oms.service.support.InClause;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

/**
 * 주문 엑셀 시딩 (수동 수집). 스트리밍 파싱 → 사전 일괄 조회 → 행 검증 → 주문 그룹핑 → 주문 검증
 * → 대량 등록(OrderRegistrationService.registerAll) → 결과 파일.
 * <p>
 * DB 조회는 행마다 하지 않는다. 사전 조회가 두 갈래이고 둘 다 Map으로 캐시한다.
 * <ul>
 *   <li>일반 행: (채널, 상품코드, 옵션코드) → 매핑 이력 IN 조회 (지정 브랜드 안에서만).
 *       매칭은 행의 주문일시가 속한 기간의 매핑 — 재업로드·늦은 업로드도 주문 당시 판매상품(구성)에 붙는다</li>
 *   <li>사은품(Y) 행: 상품 매핑 확인 대상이 아니다. 채널상품코드 = 제품 SKU이고 반드시 있다는 전제로,
 *       저장에 필요한 제품 id만 products IN 조회로 얻는다 (지정 브랜드 제품 안에서만)</li>
 * </ul>
 * 업로드 시 회사·브랜드를 지정한다 (한 주문 = 단일 브랜드). 다른 브랜드에 같은 채널 상품코드가 있어도 섞이지 않고,
 * 이 브랜드에 매핑이 없으면 매핑안됨으로 등록된다.
 * <p>
 * 결과 표기 규칙 (주문 단위 원자성의 귀결):
 * <ul>
 *   <li>성공: 그 주문의 모든 행에 "성공: {주문번호}"</li>
 *   <li>매핑안됨: 채널상품 매핑이 없는 일반 항목은 오류가 아니다. 주문은 매핑안됨으로 등록되고 모든 행에
 *       "성공(매핑안됨): {주문번호}", 매핑 없는 행에는 사유를 덧붙인다</li>
 *   <li>스킵: 이미 등록된 (채널, 채널주문번호) — 모든 행에 "스킵: 이미 등록된 주문"</li>
 *   <li>실패: 그 주문의 모든 행에 실패 표기. 오류 원인 행에는 구체적 사유,
 *       나머지 행에는 "동일 주문 내 다른 행 오류로 미처리". 원인 행만 표기하면 안 된다
 *       (나머지 행이 등록된 것처럼 보인다)</li>
 * </ul>
 */
@Service
public class OrderSeedService {

    private static final Logger log = LoggerFactory.getLogger(OrderSeedService.class);

    static final String DEFAULT_CURRENCY = "JPY";
    static final String SIBLING_FAILED = "동일 주문 내 다른 행 오류로 미처리";
    static final String SKIPPED = "이미 등록된 주문";

    private final SalesChannelRepository salesChannelRepository;
    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final ChannelProductResolver channelProductResolver;
    private final OrderRegistrationService orderRegistrationService;

    public OrderSeedService(SalesChannelRepository salesChannelRepository, BrandRepository brandRepository,
                            ProductRepository productRepository, ChannelProductResolver channelProductResolver,
                            OrderRegistrationService orderRegistrationService) {
        this.salesChannelRepository = salesChannelRepository;
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.channelProductResolver = channelProductResolver;
        this.orderRegistrationService = orderRegistrationService;
    }

    public byte[] template() {
        List<SalesChannel> channels = salesChannelRepository.findAll().stream()
                .filter(SalesChannel::isActive)
                .toList();
        return OrderSeedTemplateWriter.write(channels);
    }

    /**
     * @throws NotFoundException        브랜드가 없음 (@ScopeCheck)
     * @throws ForbiddenException       업로더 스코프 밖 브랜드 (@ScopeCheck)
     * @throws InvalidRequestException  계약종료 브랜드
     * @throws InvalidSeedFileException 파일 자체를 처리할 수 없음 (행 단위 오류는 결과 파일에 기록)
     */
    @ScopeCheck(ScopeTarget.BRAND)
    public OrderSeedResult seed(InputStream in, @ScopeId Long brandId) {
        Brand brand = validateTarget(brandId);
        // 단계별 소요 시간 (README 성능 수치의 출처 — 완료 로그에 함께 남긴다)
        StopWatch watch = new StopWatch();
        watch.start("parse");
        OrderSeedSheet sheet = OrderSeedSheet.read(in);
        List<OrderSeedRow> rows = sheet.readRows();
        watch.stop();
        if (rows.isEmpty()) {
            throw new InvalidSeedFileException("데이터 행이 없습니다.");
        }

        watch.start("validateRows");
        new RowValidator(loadChannels(), brand.getId()).validate(rows);
        watch.stop();
        watch.start("validateOrders");
        List<List<OrderSeedRow>> orders = groupByOrder(rows);

        List<List<OrderSeedRow>> valid = new ArrayList<>();
        int failed = 0;
        for (List<OrderSeedRow> orderRows : orders) {
            validateCommonInfo(orderRows);
            if (orderRows.stream().anyMatch(OrderSeedRow::hasErrors)) {
                markFailed(orderRows, sheet);
                failed++;
            } else {
                valid.add(orderRows);
            }
        }

        watch.stop();

        watch.start("register");
        List<OrderRegistrationResult> results = orderRegistrationService.registerAll(
                valid.stream().map(orderRows -> toCommand(orderRows, brand.getId())).toList());
        watch.stop();

        watch.start("writeResult");

        int success = 0;
        int unmapped = 0;
        int skipped = 0;
        for (int i = 0; i < valid.size(); i++) {
            List<OrderSeedRow> orderRows = valid.get(i);
            OrderRegistrationResult result = results.get(i);
            switch (result.status()) {
                case REGISTERED -> {
                    if (result.mappingPending()) {
                        markUnmapped(orderRows, sheet, result.orderNo());
                        unmapped++;
                    } else {
                        markAll(orderRows, sheet, ResultKind.SUCCESS, "성공: " + result.orderNo());
                        success++;
                    }
                }
                case DUPLICATE -> {
                    markAll(orderRows, sheet, ResultKind.SKIPPED, "스킵: " + SKIPPED);
                    skipped++;
                }
                case FAILED -> {
                    markAll(orderRows, sheet, ResultKind.FAILED, "실패: " + result.message());
                    failed++;
                }
            }
        }
        byte[] resultFile = sheet.toBytes();
        watch.stop();
        log.info("주문 시딩 완료: rows={}, orders={}, success={}, unmapped={}, skipped={}, failed={}, elapsedMs={}, phasesMs={}",
                rows.size(), orders.size(), success, unmapped, skipped, failed, watch.getTotalTimeMillis(),
                phases(watch));
        return new OrderSeedResult(resultFile, success, unmapped, skipped, failed);
    }

    /** parse=120, validateRows=80, ... (ms) */
    private static String phases(StopWatch watch) {
        return Arrays.stream(watch.getTaskInfo())
                .map(task -> task.getTaskName() + "=" + task.getTimeMillis())
                .collect(Collectors.joining(", ", "{", "}"));
    }

    private Brand validateTarget(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new NotFoundException("브랜드를 찾을 수 없습니다. brandId=" + brandId));
        if (brand.isTerminated()) {
            throw new InvalidRequestException("계약종료된 브랜드입니다. brandId=" + brandId);
        }
        return brand;
    }

    // ---------------------------------------------------------------- 주문 단위 처리

    /**
     * 주문 공통 정보는 첫 행 기준. 다른 행은 원인 행(불일치 행)에 오류를 단다.
     * 값을 읽지 못한 컬럼은 이미 행 오류로 보고됐으므로 비교하지 않는다.
     */
    private static void validateCommonInfo(List<OrderSeedRow> orderRows) {
        OrderSeedRow first = orderRows.getFirst();
        for (OrderSeedRow row : orderRows.subList(1, orderRows.size())) {
            List<String> mismatched = new ArrayList<>();
            for (OrderSeedColumn column : OrderSeedColumn.values()) {
                if (!column.isOrderLevel() || first.isInvalid(column) || row.isInvalid(column)) {
                    continue;
                }
                if (!sameValue(first.get(column), row.get(column))) {
                    mismatched.add(column.getLabel());
                }
            }
            if (!mismatched.isEmpty()) {
                row.addError("주문 공통정보가 첫 행(" + (first.rowIndex() + 1) + "행)과 다름: "
                        + String.join(", ", mismatched));
            }
        }
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    private static OrderRegistrationCommand toCommand(List<OrderSeedRow> orderRows, Long brandId) {
        OrderSeedRow first = orderRows.getFirst();
        List<OrderRegistrationCommand.Item> items = orderRows.stream().map(OrderSeedService::toItem).toList();
        return new OrderRegistrationCommand(
                first.salesChannelId(),
                first.text(OrderSeedColumn.CHANNEL_ORDER_NO),
                brandId,
                first.amount(OrderSeedColumn.TOTAL_ITEM_AMOUNT),
                first.amount(OrderSeedColumn.PAID_AMOUNT),
                first.text(OrderSeedColumn.CURRENCY),
                first.text(OrderSeedColumn.ORDERER_NAME),
                first.text(OrderSeedColumn.RECEIVER_NAME),
                first.text(OrderSeedColumn.RECEIVER_PHONE),
                first.text(OrderSeedColumn.RECEIVER_ZIPCODE),
                first.text(OrderSeedColumn.RECEIVER_ADDRESS),
                first.text(OrderSeedColumn.DELIVERY_MEMO),
                first.dateTime(OrderSeedColumn.ORDERED_AT),
                items);
    }

    /** 채널상품코드·옵션코드·단가는 수신값 그대로 (사은품도) */
    private static OrderRegistrationCommand.Item toItem(OrderSeedRow row) {
        String code = row.text(OrderSeedColumn.CHANNEL_PRODUCT_CODE);
        String optionCode = SaleProductChannelMapping.normalizeOptionCode(row.text(OrderSeedColumn.OPTION_CODE));
        int quantity = row.integer(OrderSeedColumn.QUANTITY);
        BigDecimal unitPrice = row.amount(OrderSeedColumn.UNIT_PRICE);
        return row.isGift()
                ? OrderRegistrationCommand.Item.ofGift(row.giftProductId(), code, optionCode, quantity, unitPrice)
                : OrderRegistrationCommand.Item.ofSaleProduct(row.saleProductId(), code, optionCode, quantity,
                unitPrice);
    }

    /** 원인 행엔 구체적 사유, 나머지 행엔 "동일 주문 내 다른 행 오류로 미처리" */
    private static void markFailed(List<OrderSeedRow> orderRows, OrderSeedSheet sheet) {
        for (OrderSeedRow row : orderRows) {
            String reason = row.hasErrors() ? String.join("; ", row.errors()) : SIBLING_FAILED;
            sheet.writeResult(row.rowIndex(), ResultKind.FAILED, "실패: " + reason);
        }
    }

    /** 모든 행에 매핑안됨 표기, 매핑 없는 행에는 사유를 덧붙인다 */
    private static void markUnmapped(List<OrderSeedRow> orderRows, OrderSeedSheet sheet, String orderNo) {
        for (OrderSeedRow row : orderRows) {
            String message = "성공(매핑안됨): " + orderNo + (row.isUnmapped() ? " - " + row.unmappedReason() : "");
            sheet.writeResult(row.rowIndex(), ResultKind.UNMAPPED, message);
        }
    }

    private static void markAll(List<OrderSeedRow> orderRows, OrderSeedSheet sheet, ResultKind kind, String message) {
        orderRows.forEach(row -> sheet.writeResult(row.rowIndex(), kind, message));
    }

    /**
     * (채널코드, 채널주문번호)로 묶는다. 행이 떨어져 있어도 같은 주문이고, 첫 등장 순서를 유지한다.
     * 둘 중 하나라도 비어 있는 행은 묶을 수 없으므로 단독 그룹 (이미 필수값 오류).
     */
    private static List<List<OrderSeedRow>> groupByOrder(List<OrderSeedRow> rows) {
        Map<String, List<OrderSeedRow>> groups = new LinkedHashMap<>();
        for (OrderSeedRow row : rows) {
            String channelCode = row.text(OrderSeedColumn.CHANNEL_CODE);
            String channelOrderNo = row.text(OrderSeedColumn.CHANNEL_ORDER_NO);
            String key = channelCode == null || channelOrderNo == null
                    ? "#row-" + row.rowIndex()
                    : channelCode + '\u0000' + channelOrderNo;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        return new ArrayList<>(groups.values());
    }

    private Map<String, SalesChannel> loadChannels() {
        return salesChannelRepository.findAll().stream()
                .collect(Collectors.toMap(SalesChannel::getCode, Function.identity()));
    }

    // ---------------------------------------------------------------- 행 단위 DB 검증

    /**
     * 1) 채널 확정 → 2) 사전 일괄 조회 두 갈래(매핑 IN / 사은품 SKU IN) → 3) 행별 판매상품·제품 확정.
     */
    private final class RowValidator {

        private final Map<String, SalesChannel> channels;
        private final Long brandId;

        RowValidator(Map<String, SalesChannel> channels, Long brandId) {
            this.channels = channels;
            this.brandId = brandId;
        }

        void validate(List<OrderSeedRow> rows) {
            rows.forEach(this::validateBasics);

            List<OrderSeedRow> productRows = rows.stream().filter(this::resolvable).toList();
            Set<MappingKey> mappingKeys = new HashSet<>();
            Set<String> giftSkus = new HashSet<>();
            for (OrderSeedRow row : productRows) {
                if (row.isGift()) {
                    giftSkus.add(row.text(OrderSeedColumn.CHANNEL_PRODUCT_CODE));
                } else {
                    mappingKeys.add(mappingKey(row));
                }
            }
            MappingHistory mappings = channelProductResolver.loadHistory(brandId, mappingKeys);
            Map<String, Long> giftProductIds = loadProductIdsBySku(giftSkus);

            for (OrderSeedRow row : productRows) {
                if (row.isGift()) {
                    resolveGift(row, giftProductIds);
                } else {
                    resolveMapping(row, mappings);
                }
            }
        }

        private void validateBasics(OrderSeedRow row) {
            if (row.get(OrderSeedColumn.CURRENCY) == null && !row.isInvalid(OrderSeedColumn.CURRENCY)) {
                row.put(OrderSeedColumn.CURRENCY, DEFAULT_CURRENCY);
            }
            if (row.get(OrderSeedColumn.GIFT) == null && !row.isInvalid(OrderSeedColumn.GIFT)) {
                row.put(OrderSeedColumn.GIFT, Boolean.FALSE);
            }
            String channelCode = row.text(OrderSeedColumn.CHANNEL_CODE);
            if (channelCode == null) {
                return;
            }
            SalesChannel channel = channels.get(channelCode);
            if (channel == null) {
                row.reject(OrderSeedColumn.CHANNEL_CODE, "채널코드: 존재하지 않는 채널 (" + channelCode + ")");
            } else if (!channel.isActive()) {
                row.reject(OrderSeedColumn.CHANNEL_CODE, "채널코드: 비활성 채널 (" + channelCode + ")");
            } else {
                row.resolveSalesChannel(channel.getId());
            }
        }

        /** 상품 확정에 필요한 값이 모두 읽힌 행만 조회 대상 (주문일시는 매핑 기간 매칭에 필요) */
        private boolean resolvable(OrderSeedRow row) {
            return row.salesChannelId() != null
                    && row.dateTime(OrderSeedColumn.ORDERED_AT) != null
                    && row.text(OrderSeedColumn.CHANNEL_PRODUCT_CODE) != null
                    && !row.isInvalid(OrderSeedColumn.OPTION_CODE)
                    && !row.isInvalid(OrderSeedColumn.GIFT);
        }

        /** 옵션코드 빈 값은 매핑 테이블 규칙대로 ''로 정규화해서 조회 (옵션 없는 매핑 = '') */
        private MappingKey mappingKey(OrderSeedRow row) {
            return MappingKey.of(row.salesChannelId(), row.text(OrderSeedColumn.CHANNEL_PRODUCT_CODE),
                    row.text(OrderSeedColumn.OPTION_CODE));
        }

        /** 범위 밖(다른 브랜드)·미등록·주문일시 구간 밖을 구분하지 않고 같은 사유 */
        private void resolveMapping(OrderSeedRow row, MappingHistory mappings) {
            MappingKey key = mappingKey(row);
            mappings.saleProductIdAt(key, row.dateTime(OrderSeedColumn.ORDERED_AT)).ifPresentOrElse(
                    row::resolveSaleProduct,
                    () -> row.markUnmapped("선택한 브랜드에 등록된 매핑이 없습니다"
                            + " (채널=" + row.text(OrderSeedColumn.CHANNEL_CODE) + ", 상품코드=" + key.code()
                            + ", 옵션코드=" + (key.optionCode().isEmpty() ? "없음" : key.optionCode()) + ")"));
        }

        /**
         * 사은품은 매핑 확인을 하지 않는다 (SKU는 반드시 있다는 전제). 주문 항목은 제품 id로 저장하므로 id만 꺼낸다.
         * 전제가 깨진 경우(SKU 오타 등)는 저장할 제품이 없으므로 안전장치로만 행 오류 처리한다.
         */
        private void resolveGift(OrderSeedRow row, Map<String, Long> productIds) {
            String sku = row.text(OrderSeedColumn.CHANNEL_PRODUCT_CODE);
            Long productId = productIds.get(sku);
            if (productId == null) {
                row.reject(OrderSeedColumn.CHANNEL_PRODUCT_CODE, "사은품 SKU가 브랜드 제품에 없음 (SKU=" + sku + ")");
                return;
            }
            row.resolveGiftProduct(productId);
        }

        private Map<String, Long> loadProductIdsBySku(Set<String> skus) {
            Map<String, Long> productIds = new HashMap<>();
            for (List<String> chunk : InClause.partition(skus)) {
                productRepository.findByBrandIdAndSkuIn(brandId, chunk)
                        .forEach(p -> productIds.put(p.getSku(), p.getId()));
            }
            return productIds;
        }
    }
}
