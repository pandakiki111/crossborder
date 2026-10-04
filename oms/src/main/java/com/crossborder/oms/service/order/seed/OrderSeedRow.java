package com.crossborder.oms.service.order.seed;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 엑셀 데이터 행 1개 = 주문 항목 1개. 파싱된 값과 이 행에서 발견된 오류를 함께 담는다.
 */
class OrderSeedRow {

    /** 시트의 0-based 행 인덱스 (결과 기록 위치) */
    private final int rowIndex;
    private final Map<OrderSeedColumn, Object> values = new EnumMap<>(OrderSeedColumn.class);
    /** 값을 읽지 못한 컬럼 — 주문 공통 정보 비교에서 제외한다 (같은 원인을 두 번 보고하지 않도록) */
    private final Set<OrderSeedColumn> invalidColumns = EnumSet.noneOf(OrderSeedColumn.class);
    private final List<String> errors = new ArrayList<>();

    private Long salesChannelId;
    private Long saleProductId;
    /** 사은품 행: SKU로 확정된 제품 */
    private Long giftProductId;
    /** 채널상품 매핑이 없을 때 사유. 오류가 아니라 매핑안됨 항목으로 등록된다 */
    private String unmappedReason;

    OrderSeedRow(int rowIndex) {
        this.rowIndex = rowIndex;
    }

    int rowIndex() {
        return rowIndex;
    }

    void put(OrderSeedColumn column, Object value) {
        if (value != null) {
            values.put(column, value);
        }
    }

    Object get(OrderSeedColumn column) {
        return values.get(column);
    }

    String text(OrderSeedColumn column) {
        return (String) values.get(column);
    }

    BigDecimal amount(OrderSeedColumn column) {
        return (BigDecimal) values.get(column);
    }

    Integer integer(OrderSeedColumn column) {
        return (Integer) values.get(column);
    }

    LocalDateTime dateTime(OrderSeedColumn column) {
        return (LocalDateTime) values.get(column);
    }

    void reject(OrderSeedColumn column, String message) {
        invalidColumns.add(column);
        errors.add(message);
    }

    void addError(String message) {
        errors.add(message);
    }

    boolean isInvalid(OrderSeedColumn column) {
        return invalidColumns.contains(column);
    }

    boolean hasErrors() {
        return !errors.isEmpty();
    }

    List<String> errors() {
        return Collections.unmodifiableList(errors);
    }

    Long salesChannelId() {
        return salesChannelId;
    }

    void resolveSalesChannel(Long salesChannelId) {
        this.salesChannelId = salesChannelId;
    }

    Long saleProductId() {
        return saleProductId;
    }

    void resolveSaleProduct(Long saleProductId) {
        this.saleProductId = saleProductId;
    }

    boolean isGift() {
        return Boolean.TRUE.equals(values.get(OrderSeedColumn.GIFT));
    }

    Long giftProductId() {
        return giftProductId;
    }

    void resolveGiftProduct(Long productId) {
        this.giftProductId = productId;
    }

    void markUnmapped(String reason) {
        this.unmappedReason = reason;
    }

    boolean isUnmapped() {
        return unmappedReason != null;
    }

    String unmappedReason() {
        return unmappedReason;
    }
}
