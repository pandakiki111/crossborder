package com.crossborder.oms.service.order;

import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.oms.config.OrderSearchProperties;
import com.crossborder.oms.dto.CappedPageResponse;
import com.crossborder.oms.dto.order.OrderDetailResponse;
import com.crossborder.oms.dto.order.OrderSearchCondition;
import com.crossborder.oms.dto.order.OrderSearchCondition.SkuMatch;
import com.crossborder.oms.dto.order.OrderSummaryResponse;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.repository.OrderQueryRepository;
import com.crossborder.oms.repository.OrderQueryRepository.Row;
import com.crossborder.oms.repository.OrderSearchCriteria;
import com.crossborder.oms.repository.OrderSearchCriteria.SkuFilter;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.security.scope.ScopeCheck;
import com.crossborder.oms.security.scope.ScopeId;
import com.crossborder.oms.security.scope.ScopeTarget;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 주문 조회 (목록·상세).
 * <p>
 * 조회 스코프: ADMIN 전체 / WORKER 불가 / COMPANY_STAFF는 orders.company_id / BRAND_STAFF는 order_items.brand_id 기준.
 * <p>
 * 목록 제약(1,000만 건 대비, 값은 OrderSearchProperties): 기간은 항상 적용(기본 최근 N일, 최대 일수),
 * 부분 일치는 더 짧은 기간만, 페이지 크기·offset 상한, 건수는 상한까지만 센다.
 */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderQueryRepository orderQueryRepository;
    private final SalesChannelCodes salesChannelCodes;
    private final OrderSearchProperties properties;
    private final Clock clock;

    public OrderQueryService(OrderQueryRepository orderQueryRepository, SalesChannelCodes salesChannelCodes,
                             OrderSearchProperties properties, Clock clock) {
        this.orderQueryRepository = orderQueryRepository;
        this.salesChannelCodes = salesChannelCodes;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 매핑안됨 주문은 상태(PAID·PARTIAL_CANCELED 등)와 별개로 mappingPending=true, unmappedItemCount > 0 으로 나온다.
     */
    public CappedPageResponse<OrderSummaryResponse> search(OrderSearchCondition condition, Pageable pageable,
                                                           AuthenticatedUser user) {
        validatePage(pageable);
        OrderSearchCriteria criteria = toCriteria(condition, user);
        int size = pageable.getPageSize();
        if (criteria.sku() != null && criteria.sku().matchesNothing()) {
            return new CappedPageResponse<>(List.of(), pageable.getPageNumber(), size, 0, false);
        }

        List<OrderSummaryResponse> content = orderQueryRepository
                .findPage(criteria, user, pageable.getOffset(), size).stream()
                .map(this::toResponse)
                .toList();

        // 마지막 페이지면 건수를 따로 세지 않는다
        long total;
        if (content.size() < size && (pageable.getOffset() == 0 || !content.isEmpty())) {
            total = pageable.getOffset() + content.size();
        } else {
            total = orderQueryRepository.countUpTo(criteria, user, properties.countCap() + 1);
        }
        boolean capped = total > properties.countCap();
        return new CappedPageResponse<>(content, pageable.getPageNumber(), size,
                capped ? properties.countCap() : total, capped);
    }

    /**
     * TODO: 항목(판매상품명/제품명 조인, 매핑안됨 항목은 채널 코드) + 회차·회차항목
     */
    @ScopeCheck(ScopeTarget.ORDER)
    public OrderDetailResponse getDetail(@ScopeId Long orderId, AuthenticatedUser user) {
        throw new UnsupportedOperationException("주문 상세 미구현");
    }

    private void validatePage(Pageable pageable) {
        if (pageable.getPageSize() > properties.maxPageSize()) {
            throw new InvalidRequestException("페이지 크기는 최대 %,d입니다".formatted(properties.maxPageSize()));
        }
        if (pageable.getOffset() > properties.maxOffset()) {
            throw new InvalidRequestException(
                    "%,d번째 이후 행은 조회할 수 없습니다. 기간이나 조건을 좁혀 다시 조회하세요".formatted(properties.maxOffset()));
        }
    }

    private OrderSearchCriteria toCriteria(OrderSearchCondition c, AuthenticatedUser user) {
        LocalDate today = LocalDate.now(clock);
        LocalDate to = c.orderedTo() != null ? c.orderedTo() : today;
        LocalDate from = c.orderedFrom() != null ? c.orderedFrom() : to.minusDays(properties.defaultPeriodDays() - 1L);
        if (from.isAfter(to)) {
            throw new InvalidRequestException("주문일 시작이 끝보다 늦습니다");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > properties.maxPeriodDays()) {
            throw new InvalidRequestException("조회 기간은 최대 %d일입니다".formatted(properties.maxPeriodDays()));
        }

        List<String> channelOrderNos = c.channelOrderNos() == null ? List.of() : c.channelOrderNos().stream()
                .filter(StringUtils::hasText).map(String::trim).distinct().toList();
        if (channelOrderNos.size() > properties.maxChannelOrderNos()) {
            throw new InvalidRequestException(
                    "주문번호는 한 번에 최대 %d개까지 검색할 수 있습니다".formatted(properties.maxChannelOrderNos()));
        }
        String channelOrderNoContains = trimToNull(c.channelOrderNoContains());
        String sku = trimToNull(c.sku());
        boolean partialSku = sku != null && c.skuMatch() == SkuMatch.PARTIAL;
        if ((channelOrderNoContains != null || partialSku) && days > properties.partialMatchMaxDays()) {
            throw new InvalidRequestException(
                    "부분 일치 검색은 기간을 최대 %d일로 지정해야 합니다".formatted(properties.partialMatchMaxDays()));
        }
        SkuFilter skuFilter = sku == null ? null
                : orderQueryRepository.resolveSku(sku, partialSku, properties.skuSparseItemThreshold());

        return new OrderSearchCriteria(
                from.atStartOfDay(),
                to.plusDays(1).atStartOfDay(),
                distinct(c.status()),
                c.mappingPending(),
                c.salesChannelId(),
                // BRAND_STAFF는 스코프가 자기 브랜드로 고정한다
                user.role() == UserRole.BRAND_STAFF ? null : c.brandId(),
                distinct(c.shipmentStatus()),
                Boolean.TRUE.equals(c.unsplit()),
                trimToNull(c.orderNo()),
                channelOrderNos,
                channelOrderNoContains,
                skuFilter);
    }

    private OrderSummaryResponse toResponse(Row row) {
        return new OrderSummaryResponse(row.orderId(), row.orderNo(), salesChannelCodes.codeOf(row.salesChannelId()),
                row.channelOrderNo(), row.status(), row.mappingPending(), row.ordererName(), row.receiverName(),
                row.paidAmount(), row.currency(), row.itemCount(), row.unmappedItemCount(), row.orderedAt());
    }

    private static <T> List<T> distinct(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
