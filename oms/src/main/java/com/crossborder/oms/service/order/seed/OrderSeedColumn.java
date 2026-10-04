package com.crossborder.oms.service.order.seed;

import java.util.Arrays;
import java.util.Optional;

/**
 * 주문 시딩 엑셀 컬럼. 행 1개 = 주문 항목 1개, 같은 (채널코드, 채널주문번호) 행들이 한 주문이다.
 * <p>
 * orderLevel = 주문 공통 정보. 같은 주문의 행들은 첫 행과 값이 같아야 한다 (다르면 검증 오류).
 * maxLength는 DB 컬럼 길이 기준.
 */
public enum OrderSeedColumn {

    CHANNEL_CODE("채널코드", true, false, Type.TEXT, 30, "작성안내 시트의 채널코드 목록 참조"),
    CHANNEL_ORDER_NO("채널주문번호", true, false, Type.TEXT, 100, "같은 채널주문번호의 행들이 한 주문의 항목이 됩니다"),
    CHANNEL_PRODUCT_CODE("채널상품코드", true, false, Type.TEXT, 100,
            "채널에 등록된 상품코드 (판매상품 채널 매핑 기준). 사은품(Y)이면 제품 SKU"),
    OPTION_CODE("옵션코드", false, false, Type.TEXT, 30, "채널 옵션코드. 옵션이 없으면 비워둡니다"),
    GIFT("사은품여부", false, false, Type.YN, null,
            "Y = 사은품 (채널상품코드를 제품 SKU로 보고 상품 매핑 안 함), N 또는 비움 = 일반 상품"),
    QUANTITY("수량", true, false, Type.INTEGER, null, "1 이상의 정수"),
    UNIT_PRICE("상품단가", true, false, Type.AMOUNT, null, "항목 단가 (마켓 수신값, 0 이상)"),
    TOTAL_ITEM_AMOUNT("정상가", true, true, Type.AMOUNT, null, "주문 상품금액 합계 (마켓 수신값, 0 이상). 같은 주문의 모든 행에 같은 값"),
    PAID_AMOUNT("실결제금액", true, true, Type.AMOUNT, null, "주문 실결제금액 (할인·포인트 반영, 0 이상). 같은 주문의 모든 행에 같은 값"),
    CURRENCY("통화", false, true, Type.TEXT, 3, "ISO 통화코드 3자리. 비우면 JPY"),
    ORDERER_NAME("주문자명", true, true, Type.TEXT, 100, "같은 주문의 모든 행에 같은 값"),
    RECEIVER_NAME("수취인명", true, true, Type.TEXT, 100, "같은 주문의 모든 행에 같은 값"),
    RECEIVER_PHONE("수취인전화", false, true, Type.TEXT, 30, "같은 주문의 모든 행에 같은 값"),
    RECEIVER_ZIPCODE("우편번호", false, true, Type.TEXT, 10, "같은 주문의 모든 행에 같은 값"),
    RECEIVER_ADDRESS("주소", true, true, Type.TEXT, 500, "같은 주문의 모든 행에 같은 값"),
    DELIVERY_MEMO("배송메모", false, true, Type.TEXT, 300, "같은 주문의 모든 행에 같은 값"),
    ORDERED_AT("주문일시", true, true, Type.DATETIME, null, "yyyy-MM-dd HH:mm:ss (예: 2026-10-01 14:30:00)");

    public enum Type {
        TEXT("텍스트"),
        INTEGER("정수"),
        AMOUNT("숫자 (소수점 2자리까지)"),
        DATETIME("yyyy-MM-dd HH:mm:ss"),
        YN("Y / N");

        private final String description;

        Type(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    private static final String REQUIRED_MARK = "*";

    private final String label;
    private final boolean required;
    private final boolean orderLevel;
    private final Type type;
    private final Integer maxLength;
    private final String description;

    OrderSeedColumn(String label, boolean required, boolean orderLevel, Type type, Integer maxLength,
                    String description) {
        this.label = label;
        this.required = required;
        this.orderLevel = orderLevel;
        this.type = type;
        this.maxLength = maxLength;
        this.description = description;
    }

    /** 엑셀 헤더 표기 (필수는 * 표시) */
    public String header() {
        return required ? label + REQUIRED_MARK : label;
    }

    /** 헤더 셀 값으로 컬럼 찾기. * 유무·앞뒤 공백은 무시한다 */
    public static Optional<OrderSeedColumn> fromHeader(String header) {
        if (header == null) {
            return Optional.empty();
        }
        String normalized = header.trim();
        if (normalized.endsWith(REQUIRED_MARK)) {
            normalized = normalized.substring(0, normalized.length() - REQUIRED_MARK.length()).trim();
        }
        String label = normalized;
        return Arrays.stream(values()).filter(c -> c.label.equals(label)).findFirst();
    }

    public String getLabel() {
        return label;
    }

    public boolean isRequired() {
        return required;
    }

    public boolean isOrderLevel() {
        return orderLevel;
    }

    public Type getType() {
        return type;
    }

    public Integer getMaxLength() {
        return maxLength;
    }

    public String getDescription() {
        return description;
    }
}
