package com.crossborder.oms.service.order.download;

import com.crossborder.infra.lock.DistributedLockManager;
import com.crossborder.infra.lock.LockAcquisitionException;
import com.crossborder.oms.config.OrderDownloadProperties;
import com.crossborder.oms.dto.order.OrderDownloadEstimate;
import com.crossborder.oms.dto.order.OrderSearchCondition;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.repository.OrderDownloadRepository;
import com.crossborder.oms.repository.OrderDownloadRepository.Cursor;
import com.crossborder.oms.repository.OrderDownloadRepository.DownloadLine;
import com.crossborder.oms.repository.OrderDownloadRepository.DownloadOrder;
import com.crossborder.oms.repository.OrderQueryRepository;
import com.crossborder.oms.repository.OrderSearchCriteria;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.order.OrderSearchCriteriaFactory;
import com.crossborder.oms.service.order.OrderSearchCriteriaFactory.PeriodRule;
import com.crossborder.oms.service.order.SalesChannelCodes;
import com.crossborder.oms.service.order.download.OrderDownloadColumn.Row;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 목록 엑셀 다운로드 — 자체 CBT를 쓰지 않는 고객사가 타사 물류 시스템에 출고지시를 올리는 경로 (README §5).
 * <p>
 * 조건은 주문 목록과 같은 빌더(OrderSearchCriteriaFactory)·같은 조건식을 쓰고, 기간만 필수·최대 일수가 다르다.
 * 커서(ordered_at, id)로 주문을 묶음 단위로 읽어 제품 단위로 전개하고 SXSSF로 쓴다 — 메모리에는 묶음 1개와
 * SXSSF 쓰기 창만 있다. 같은 사용자의 동시 다운로드는 1건 (진행 중이면 409).
 * <p>
 * 응답은 동기 스트리밍: xlsx(zip)는 마지막에 한 번에 만들어지므로 생성이 끝나야 바이트가 나간다. 그 전에 난 오류는
 * 응답이 커밋되지 않아 4xx/5xx로 돌려줄 수 있다. 진행률은 estimate 건수로 안내한다 (비동기 잡은 §8).
 */
@Service
public class OrderDownloadService {

    private static final Logger log = LoggerFactory.getLogger(OrderDownloadService.class);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderSearchCriteriaFactory criteriaFactory;
    private final OrderQueryRepository orderQueryRepository;
    private final OrderDownloadRepository downloadRepository;
    private final SalesChannelCodes salesChannelCodes;
    private final DistributedLockManager lockManager;
    private final TransactionTemplate readOnlyTransaction;
    private final OrderDownloadProperties properties;
    private final Clock clock;

    public OrderDownloadService(OrderSearchCriteriaFactory criteriaFactory, OrderQueryRepository orderQueryRepository,
                                OrderDownloadRepository downloadRepository, SalesChannelCodes salesChannelCodes,
                                DistributedLockManager lockManager, PlatformTransactionManager transactionManager,
                                OrderDownloadProperties properties, Clock clock) {
        this.criteriaFactory = criteriaFactory;
        this.orderQueryRepository = orderQueryRepository;
        this.downloadRepository = downloadRepository;
        this.salesChannelCodes = salesChannelCodes;
        this.lockManager = lockManager;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.properties = properties;
        this.clock = clock;
    }

    /** 응답 대상. 생성이 끝난 뒤 파일명·행 수와 함께 한 번 열린다 (헤더를 쓰고 출력 스트림을 돌려준다) */
    @FunctionalInterface
    public interface DownloadSink {
        OutputStream open(String fileName, int rowCount) throws IOException;
    }

    /**
     * 다운로드 전 건수 (화면의 기간 경고·소요 안내용). 주문 수를 상한+1까지만 센다.
     */
    public OrderDownloadEstimate estimate(OrderSearchCondition condition, AuthenticatedUser user) {
        OrderSearchCriteria criteria = criteriaFactory.create(condition, user, period());
        long count = countOrders(criteria, user);
        boolean exceeds = count > properties.maxOrders();
        return new OrderDownloadEstimate(exceeds ? properties.maxOrders() : count, exceeds, properties.maxOrders());
    }

    /**
     * @param columnKeys 열 키 배열 (순서 유지, 비면 전체). 주문번호·채널은 항상 포함
     * @throws InvalidRequestException 기간·조건·열 위반, 주문 수 상한·엑셀 행 한도 초과
     * @throws ConflictException       같은 사용자의 다운로드가 진행 중
     */
    public void download(OrderSearchCondition condition, List<String> columnKeys, AuthenticatedUser user,
                         DownloadSink sink) {
        List<OrderDownloadColumn> columns = OrderDownloadColumn.resolve(columnKeys);
        OrderSearchCriteria criteria = criteriaFactory.create(condition, user, period());
        try {
            lockManager.executeIfAvailable("order-download:user:" + user.userId(), () -> {
                if (countOrders(criteria, user) > properties.maxOrders()) {
                    throw new InvalidRequestException("다운로드 주문이 %,d건을 넘습니다. 기간이나 조건을 좁혀 나눠 받으세요"
                            .formatted(properties.maxOrders()));
                }
                write(criteria, columns, user, sink);
                return null;
            });
        } catch (LockAcquisitionException e) {
            throw new ConflictException("진행 중인 다운로드가 있습니다. 끝난 뒤 다시 시도하세요");
        }
    }

    private void write(OrderSearchCriteria criteria, List<OrderDownloadColumn> columns, AuthenticatedUser user,
                       DownloadSink sink) {
        long started = System.nanoTime();
        try (OrderDownloadWriter writer = new OrderDownloadWriter(columns)) {
            int orders = 0;
            if (criteria.sku() == null || !criteria.sku().matchesNothing()) {
                Cursor cursor = null;
                List<DownloadOrder> chunk;
                do {
                    Cursor after = cursor;
                    chunk = readOnlyTransaction.execute(
                            status -> downloadRepository.findOrders(criteria, user, after, properties.chunkOrders()));
                    if (chunk.isEmpty()) {
                        break;
                    }
                    List<Long> ids = chunk.stream().map(DownloadOrder::orderId).toList();
                    Map<Long, List<DownloadLine>> lines = readOnlyTransaction.execute(
                                    status -> downloadRepository.findLines(ids)).stream()
                            .collect(Collectors.groupingBy(DownloadLine::orderId));
                    for (DownloadOrder order : chunk) {
                        String channelCode = salesChannelCodes.codeOf(order.salesChannelId());
                        for (DownloadLine line : lines.getOrDefault(order.orderId(), List.of())) {
                            writer.add(new Row(order, channelCode, line));
                        }
                    }
                    orders += chunk.size();
                    DownloadOrder last = chunk.getLast();
                    cursor = new Cursor(last.orderedAt(), last.orderId());
                } while (chunk.size() == properties.chunkOrders());
            }
            long generated = System.nanoTime();
            try (OutputStream out = sink.open(fileName(criteria), writer.rowCount())) {
                writer.writeTo(out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            log.info("주문 다운로드 완료: userId={}, orders={}, rows={}, generateMs={}, writeMs={}", user.userId(), orders,
                    writer.rowCount(), (generated - started) / 1_000_000, (System.nanoTime() - generated) / 1_000_000);
        }
    }

    private long countOrders(OrderSearchCriteria criteria, AuthenticatedUser user) {
        if (criteria.sku() != null && criteria.sku().matchesNothing()) {
            return 0;
        }
        return readOnlyTransaction.execute(status -> (long) orderQueryRepository.countUpTo(criteria, user,
                properties.maxOrders() + 1));
    }

    private PeriodRule period() {
        return new PeriodRule(true, properties.maxDays(),
                "다운로드는 주문일 시작·끝을 지정해 최대 %d일 단위로 받을 수 있습니다".formatted(properties.maxDays()));
    }

    /** orders-{시작}-{끝}-{타임스탬프}.xlsx */
    private String fileName(OrderSearchCriteria criteria) {
        return "orders-%s-%s-%s.xlsx".formatted(criteria.orderedFrom().format(FILE_DATE),
                criteria.orderedTo().minusDays(1).format(FILE_DATE), LocalDateTime.now(clock).format(FILE_TIMESTAMP));
    }
}
