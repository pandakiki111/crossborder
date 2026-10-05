package com.crossborder.oms.service.order;

import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.oms.config.OrderSearchProperties;
import com.crossborder.oms.dto.order.OrderSearchCondition;
import com.crossborder.oms.dto.order.OrderSearchCondition.SkuMatch;
import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.repository.OrderQueryRepository;
import com.crossborder.oms.repository.OrderSearchCriteria;
import com.crossborder.oms.repository.OrderSearchCriteria.SkuFilter;
import com.crossborder.oms.security.AuthenticatedUser;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 주문 검색 조건 검증·정규화. 주문 목록과 주문 다운로드가 같은 규칙을 쓴다 (기간 규칙만 다르다 — {@link PeriodRule}).
 */
@Component
public class OrderSearchCriteriaFactory {

    private final OrderQueryRepository orderQueryRepository;
    private final OrderSearchProperties properties;
    private final Clock clock;

    public OrderSearchCriteriaFactory(OrderQueryRepository orderQueryRepository, OrderSearchProperties properties,
                                      Clock clock) {
        this.orderQueryRepository = orderQueryRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 기간 규칙.
     *
     * @param required 시작·끝을 모두 지정해야 함 (아니면 미지정 시 최근 기본 일수)
     * @param maxDays  최대 일수 (양끝 포함)
     * @param guide    위반 시 400 메시지
     */
    public record PeriodRule(boolean required, int maxDays, String guide) {
    }

    /** 주문 목록: 미지정이면 최근 N일, 최대 설정 일수 */
    public PeriodRule listPeriod() {
        return new PeriodRule(false, properties.maxPeriodDays(),
                "조회 기간은 최대 %d일입니다".formatted(properties.maxPeriodDays()));
    }

    /**
     * @throws InvalidRequestException 기간·부분 일치 기간·주문번호 개수 위반
     */
    public OrderSearchCriteria create(OrderSearchCondition c, AuthenticatedUser user, PeriodRule period) {
        if (period.required() && (c.orderedFrom() == null || c.orderedTo() == null)) {
            throw new InvalidRequestException(period.guide());
        }
        LocalDate to = c.orderedTo() != null ? c.orderedTo() : LocalDate.now(clock);
        LocalDate from = c.orderedFrom() != null ? c.orderedFrom() : to.minusDays(properties.defaultPeriodDays() - 1L);
        if (from.isAfter(to)) {
            throw new InvalidRequestException("주문일 시작이 끝보다 늦습니다");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > period.maxDays()) {
            throw new InvalidRequestException(period.guide());
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

    private static <T> List<T> distinct(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
