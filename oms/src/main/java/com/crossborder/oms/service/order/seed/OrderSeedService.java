package com.crossborder.oms.service.order.seed;

import com.crossborder.common.entity.organization.Brand;
import com.crossborder.common.entity.product.SaleProductChannelMapping;
import com.crossborder.common.entity.product.SalesChannel;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.ForbiddenException;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.oms.repository.BrandRepository;
import com.crossborder.oms.repository.OrderRepository;
import com.crossborder.oms.repository.ProductRepository;
import com.crossborder.oms.repository.SalesChannelRepository;
import com.crossborder.oms.security.scope.ScopeCheck;
import com.crossborder.oms.security.scope.ScopeId;
import com.crossborder.oms.security.scope.ScopeTarget;
import com.crossborder.oms.service.gift.GiftEventApplier;
import com.crossborder.oms.service.order.OrderRegistrationCommand;
import com.crossborder.oms.service.order.OrderRegistrationResult;
import com.crossborder.oms.service.order.OrderRegistrationService;
import com.crossborder.oms.service.order.seed.OrderSeedSheet.ResultKind;
import com.crossborder.oms.service.product.ChannelProductResolver;
import com.crossborder.oms.service.support.BrandWriteGuard;
import com.crossborder.oms.service.product.ChannelProductResolver.MappingHistory;
import com.crossborder.oms.service.product.ChannelProductResolver.MappingKey;
import com.crossborder.oms.service.support.InClause;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 주문 엑셀 시딩 (수동 수집). 메모리에 남는 행 데이터를 청크 1개분으로 제한하는 3-패스 파이프라인이다.
 * <ol>
 *   <li>1차 패스(인덱스): 주문키 → 행 번호만 모은다 (행 데이터 비보관). 떨어진 같은 주문 행·파일 내 중복 주문은 여기서 병합된다</li>
 *   <li>2차 패스(청크 처리): 행을 흘리며 주문이 완성되는 대로 모아 chunkOrders(기본 1,000)주문마다
 *       청크분 일괄 조회 → 행·주문 검증 → 등록+할당(ChunkedAllocationExecutor) → 결과를 압축 보관하고 청크 데이터를 버린다.
 *       청크 처리 순서는 주문 완성 순서라 원본 행 순서와 다를 수 있다</li>
 *   <li>3차 패스(결과 파일): 원본을 다시 흘리며 원본 행 순서대로 처리결과 열을 쓴다 (SXSSF)</li>
 * </ol>
 * 무거운 캐시(매핑 이력·사은품 SKU)는 청크 수명이다. 같은 상품코드가 여러 청크에 나오면 다시 조회한다 (IN 일괄이라 범청크 캐시 불요).
 * 기존 주문번호 중복 체크도 청크별 IN 일괄 (registerAll). 청크는 순차 처리한다 (락 경합·순서 복잡도 회피).
 * <p>
 * DB 조회는 행마다 하지 않는다. 청크마다 사전 조회가 두 갈래이고 둘 다 Map으로 캐시한다.
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
    static final String GIFT_FAILED = "사은품 이벤트 증정 실패 (주문은 등록됨, 재평가 필요)";

    private final SalesChannelRepository salesChannelRepository;
    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final ChannelProductResolver channelProductResolver;
    private final OrderRegistrationService orderRegistrationService;
    private final GiftEventApplier giftEventApplier;
    private final OrderRepository orderRepository;
    private final BrandWriteGuard brandWriteGuard;
    /** 청크당 주문 수 (테스트에서 경계 검증을 위해 바꿀 수 있도록 final이 아님) */
    private int chunkOrders;

    public OrderSeedService(SalesChannelRepository salesChannelRepository, BrandRepository brandRepository,
                            ProductRepository productRepository, ChannelProductResolver channelProductResolver,
                            OrderRegistrationService orderRegistrationService, GiftEventApplier giftEventApplier,
                            OrderRepository orderRepository, BrandWriteGuard brandWriteGuard,
                            @Value("${crossborder.seed.chunk-orders:1000}") int chunkOrders) {
        this.salesChannelRepository = salesChannelRepository;
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.channelProductResolver = channelProductResolver;
        this.orderRegistrationService = orderRegistrationService;
        this.giftEventApplier = giftEventApplier;
        this.orderRepository = orderRepository;
        this.brandWriteGuard = brandWriteGuard;
        if (chunkOrders < 1) {
            throw new IllegalArgumentException("crossborder.seed.chunk-orders는 1 이상이어야 합니다. value=" + chunkOrders);
        }
        this.chunkOrders = chunkOrders;
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
     * @throws ConflictException       비활성·계약종료 브랜드 (BrandWriteGuard)
     * @throws InvalidSeedFileException 파일 자체를 처리할 수 없음 (행 단위 오류는 결과 파일에 기록)
     */
    @ScopeCheck(ScopeTarget.BRAND)
    public OrderSeedResult seed(InputStream in, @ScopeId Long brandId) {
        return seed(in, brandId, false);
    }

    /**
     * @param applyGiftEvents true면 등록된 주문마다 사은품 이벤트를 판정·증정한다 (매핑안됨 항목은 조건 매칭에서만 빠진다). 청크 등록·할당 커밋 뒤 별도
     *                        트랜잭션이고 응답 전에 끝난다 — 결과 파일을 받는 시점엔 증정까지 끝나 있다.
     *                        증정 실패는 주문을 실패시키지 않고 결과 파일 해당 행에 재평가 안내를 붙인다
     */
    @ScopeCheck(ScopeTarget.BRAND)
    public OrderSeedResult seed(InputStream in, @ScopeId Long brandId, boolean applyGiftEvents) {
        Brand brand = validateTarget(brandId);
        try (SeedUpload upload = SeedUpload.save(in)) {
            return process(upload, brand.getId(), applyGiftEvents);
        }
    }

    private OrderSeedResult process(SeedUpload upload, Long brandId, boolean applyGiftEvents) {
        // 단계별 소요 시간 (README 성능 수치의 출처 — 완료 로그에 함께 남긴다). 2차 패스는 청크마다 누적한다
        Phases phases = new Phases();
        long started = System.nanoTime();
        OrderSeedSheet sheet = OrderSeedSheet.open(upload.path());

        // 1차 패스: 주문 인덱스
        SeedOrderIndex.Builder indexBuilder = new SeedOrderIndex.Builder();
        sheet.forEachDataRow((rowIndex, cells) -> {
            if (!sheet.isBlank(cells)) {
                indexBuilder.add(rowIndex, sheet.orderKey(rowIndex, cells));
            }
        });
        if (indexBuilder.rowCount() == 0) {
            throw new InvalidSeedFileException("데이터 행이 없습니다.");
        }
        SeedOrderIndex index = indexBuilder.build();
        phases.add("index", started);

        // 2차 패스: 청크 처리
        SeedResultStore results = new SeedResultStore(index);
        ChunkProcessor processor = new ChunkProcessor(index, results, new RowValidator(loadChannels(), brandId),
                brandId, applyGiftEvents, phases);
        long pass2 = System.nanoTime();
        sheet.forEachDataRow((rowIndex, cells) -> {
            if (!sheet.isBlank(cells)) {
                processor.accept(sheet.parseRow(rowIndex, cells));
            }
        });
        processor.finish();
        phases.addRemainder("validate", pass2, "register");

        // 3차 패스: 결과 파일
        long pass3 = System.nanoTime();
        byte[] resultFile = sheet.writeResultFile(results);
        phases.add("writeResult", pass3);

        Counts counts = processor.counts;
        log.info("주문 시딩 완료: rows={}, orders={}, chunks={}, success={}, unmapped={}, skipped={}, failed={}, giftGrants={}, giftFailed={}, elapsedMs={}, phasesMs={}",
                indexBuilder.rowCount(), index.orderCount(), processor.chunks, counts.success, counts.unmapped,
                counts.skipped, counts.failed, counts.giftGrants, counts.giftFailed,
                (System.nanoTime() - started) / 1_000_000, phases);
        return new OrderSeedResult(resultFile, counts.success, counts.unmapped, counts.skipped, counts.failed,
                counts.giftGrants, counts.giftFailed);
    }

    /** 주문 단위 건수 (전 청크 누적) */
    private static final class Counts {
        int success;
        int unmapped;
        int skipped;
        int failed;
        int giftGrants;
        int giftFailed;
    }

    /** 단계별 누적 시간 (ms) */
    private static final class Phases {
        private final Map<String, Long> nanos = new LinkedHashMap<>();

        void add(String phase, long startedNanos) {
            nanos.merge(phase, System.nanoTime() - startedNanos, Long::sum);
        }

        /** startedNanos 이후 경과 시간에서 이미 잰 단계(excluded)를 뺀 나머지를 phase로 */
        void addRemainder(String phase, long startedNanos, String excluded) {
            long elapsed = System.nanoTime() - startedNanos;
            nanos.put(phase, elapsed - nanos.getOrDefault(excluded, 0L));
            // 표시 순서: validate 다음 register
            Long register = nanos.remove(excluded);
            if (register != null) {
                nanos.put(excluded, register);
            }
        }

        @Override
        public String toString() {
            return nanos.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue() / 1_000_000)
                    .collect(Collectors.joining(", ", "{", "}"));
        }
    }

    /**
     * 2차 패스 행 소비자. 주문의 모든 행이 들어오면(1차 인덱스의 행 수 기준) 완성 주문으로 옮기고,
     * 완성 주문이 chunkOrders개 모이면 청크를 처리한다. 미완성 주문의 행만 청크 사이에 남는다.
     */
    private final class ChunkProcessor {

        private final SeedOrderIndex index;
        private final SeedResultStore results;
        private final RowValidator rowValidator;
        private final Long brandId;
        private final boolean applyGiftEvents;
        private final Phases phases;
        private final Map<Integer, List<OrderSeedRow>> pending = new HashMap<>();
        private final List<List<OrderSeedRow>> ready = new ArrayList<>();
        private final Counts counts = new Counts();
        private int chunks;

        ChunkProcessor(SeedOrderIndex index, SeedResultStore results, RowValidator rowValidator, Long brandId,
                       boolean applyGiftEvents, Phases phases) {
            this.index = index;
            this.results = results;
            this.rowValidator = rowValidator;
            this.brandId = brandId;
            this.applyGiftEvents = applyGiftEvents;
            this.phases = phases;
        }

        void accept(OrderSeedRow row) {
            int seq = index.orderOf(row.rowIndex());
            List<OrderSeedRow> orderRows = pending.computeIfAbsent(seq, k -> new ArrayList<>());
            orderRows.add(row);
            if (orderRows.size() == index.rowCountOf(seq)) {
                pending.remove(seq);
                ready.add(orderRows);
                if (ready.size() >= chunkOrders) {
                    processChunk();
                }
            }
        }

        void finish() {
            if (!ready.isEmpty()) {
                processChunk();
            }
            if (!pending.isEmpty()) {
                // 1차·2차 패스가 같은 파일을 같은 규칙으로 읽으므로 생길 수 없다
                throw new IllegalStateException("완성되지 않은 주문이 남았습니다. orders=" + pending.keySet());
            }
        }

        /** 청크분 일괄 조회 → 행·주문 검증 → 등록+할당 → 결과 보관. 끝나면 청크 데이터를 버린다 */
        private void processChunk() {
            chunks++;
            rowValidator.validate(ready.stream().flatMap(List::stream).toList());

            List<List<OrderSeedRow>> valid = new ArrayList<>();
            for (List<OrderSeedRow> orderRows : ready) {
                validateCommonInfo(orderRows);
                if (orderRows.stream().anyMatch(OrderSeedRow::hasErrors)) {
                    recordFailed(orderRows);
                    counts.failed++;
                } else {
                    valid.add(orderRows);
                }
            }

            long registerStarted = System.nanoTime();
            List<OrderRegistrationResult> registered = orderRegistrationService.registerAll(
                    valid.stream().map(orderRows -> toCommand(orderRows, brandId)).toList());
            phases.add("register", registerStarted);

            for (int i = 0; i < valid.size(); i++) {
                List<OrderSeedRow> orderRows = valid.get(i);
                OrderRegistrationResult result = registered.get(i);
                int seq = seqOf(orderRows);
                switch (result.status()) {
                    case REGISTERED -> {
                        if (result.mappingPending()) {
                            results.recordOrder(seq, ResultKind.UNMAPPED, result.orderNo());
                            orderRows.stream().filter(OrderSeedRow::isUnmapped)
                                    .forEach(row -> results.recordRowDetail(row.rowIndex(), row.unmappedReason()));
                            counts.unmapped++;
                        } else {
                            results.recordOrder(seq, ResultKind.SUCCESS, result.orderNo());
                            counts.success++;
                        }
                    }
                    case DUPLICATE -> {
                        results.recordOrder(seq, ResultKind.SKIPPED, null);
                        counts.skipped++;
                    }
                    case FAILED -> {
                        results.recordOrder(seq, ResultKind.FAILED, result.message());
                        counts.failed++;
                    }
                }
            }
            if (applyGiftEvents) {
                applyGifts(valid, registered);
            }
            ready.clear();
        }

        /** 등록된 주문(매핑안됨 포함)을 파일 순서대로 판정·증정 (선착순 한도의 순서) */
        private void applyGifts(List<List<OrderSeedRow>> valid, List<OrderRegistrationResult> registered) {
            long started = System.nanoTime();
            Map<String, Integer> seqByOrderNo = new LinkedHashMap<>();
            for (int i = 0; i < valid.size(); i++) {
                OrderRegistrationResult result = registered.get(i);
                if (result.status() == OrderRegistrationResult.Status.REGISTERED) {
                    seqByOrderNo.put(result.orderNo(), seqOf(valid.get(i)));
                }
            }
            if (seqByOrderNo.isEmpty()) {
                return;
            }
            Map<String, Long> ids = new HashMap<>();
            orderRepository.findIdsByOrderNoIn(seqByOrderNo.keySet())
                    .forEach(row -> ids.put((String) row[1], (Long) row[0]));
            Map<Long, Integer> seqById = new LinkedHashMap<>();
            seqByOrderNo.forEach((orderNo, seq) -> seqById.put(ids.get(orderNo), seq));
            GiftEventApplier.Outcome outcome = giftEventApplier.apply(List.copyOf(seqById.keySet()));
            counts.giftGrants += outcome.grants();
            outcome.failedOrders().forEach(orderId -> {
                results.recordGiftFailed(seqById.get(orderId));
                counts.giftFailed++;
            });
            phases.add("gift", started);
        }

        /** 원인 행엔 구체적 사유, 나머지 행은 "동일 주문 내 다른 행 오류로 미처리" (SeedResultStore가 조립) */
        private void recordFailed(List<OrderSeedRow> orderRows) {
            results.recordOrder(seqOf(orderRows), ResultKind.FAILED, null);
            orderRows.stream().filter(OrderSeedRow::hasErrors)
                    .forEach(row -> results.recordRowDetail(row.rowIndex(), String.join("; ", row.errors())));
        }

        private int seqOf(List<OrderSeedRow> orderRows) {
            return index.orderOf(orderRows.getFirst().rowIndex());
        }
    }

    /** 비활성·계약종료 브랜드에는 신규 주문을 등록하지 않는다 (409, BrandWriteGuard) */
    private Brand validateTarget(Long brandId) {
        return brandWriteGuard.requireWritable(brandId);
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
                // 결제일시 미입력 = 주문일시 (A-1 폴백 저장)
                first.dateTime(OrderSeedColumn.PAID_AT) != null ? first.dateTime(OrderSeedColumn.PAID_AT)
                        : first.dateTime(OrderSeedColumn.ORDERED_AT),
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
            // 청크 수명 캐시: 이 청크 처리가 끝나면 버려진다
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
