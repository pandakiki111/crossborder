package com.crossborder.oms.service.order.download;

import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.repository.OrderDownloadRepository.DownloadLine;
import com.crossborder.oms.repository.OrderDownloadRepository.DownloadOrder;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 주문 다운로드 열의 단일 정의 (헤더·너비·값). 시딩 양식(OrderSeedColumn)과는 별개다 — 시딩은 업로드 입력, 이건 출고지시용 추출.
 * <p>
 * 행 = 제품 전개 1행 (주문·항목 열은 반복 표기). 요청은 키(enum 이름) 배열로 열과 순서를 고르고,
 * 미지정이면 전체를 이 순서로 쓴다. 주문번호·채널은 항상 포함한다 (요청에 없으면 맨 앞에 붙인다).
 */
public enum OrderDownloadColumn {

    // 주문 레벨
    ORDER_NO("주문번호", 24, Type.TEXT, r -> r.order().orderNo()),
    CHANNEL("채널", 12, Type.TEXT, Row::channelCode),
    CHANNEL_ORDER_NO("마켓주문번호", 22, Type.TEXT, r -> r.order().channelOrderNo()),
    ORDERED_AT("주문일시", 20, Type.TEXT, r -> r.order().orderedAt().format(Row.DATE_TIME)),
    PAID_AT("결제일시", 20, Type.TEXT, r -> r.order().paidAt() == null ? null : r.order().paidAt().format(Row.DATE_TIME)),
    ORDER_STATUS("주문상태", 16, Type.TEXT, r -> r.order().status().name()),
    // 항목 레벨
    BRAND("브랜드", 16, Type.TEXT, r -> r.line().brandName()),
    SALE_PRODUCT_CODE("판매상품코드", 18, Type.TEXT, r -> r.line().saleProductCode()),
    PRODUCT_NAME("상품명", 30, Type.TEXT, r -> r.line().productName()),
    CHANNEL_PRODUCT_CODE("채널상품코드", 18, Type.TEXT, r -> r.line().channelProductCode()),
    OPTION("옵션", 12, Type.TEXT, r -> r.line().channelOptionCode()),
    ITEM_QUANTITY("항목수량", 10, Type.NUMBER, r -> r.line().itemQuantity()),
    UNIT_PRICE("단가", 12, Type.NUMBER, r -> r.line().unitPrice()),
    // 전개 레벨
    SKU("SKU", 20, Type.TEXT, r -> r.line().sku()),
    PRODUCT_QUANTITY("제품수량", 10, Type.NUMBER, r -> r.line().productQuantity()),
    GIFT("사은품", 8, Type.TEXT, r -> r.line().gift() ? "Y" : "N"),
    /** COMPOSITION(구성 고정) / COLLECTED(채널 수신·시딩) / EVENT(이벤트 증정) / MANUAL(수동 증정) */
    GIFT_SOURCE("사은품출처", 14, Type.TEXT, r -> r.line().giftSource()),
    /** EVENT 행만 "[이벤트코드] 이벤트명" — 증정이 잘못됐을 때 파일에서 바로 어느 이벤트인지 추적 */
    GIFT_EVENT("사은품이벤트", 24, Type.TEXT, r -> r.line().giftEventName()),
    // 수취인
    RECEIVER_NAME("수취인", 14, Type.TEXT, r -> r.order().receiverName()),
    RECEIVER_PHONE("수취인연락처", 16, Type.TEXT, r -> r.order().receiverPhone()),
    RECEIVER_ZIPCODE("우편번호", 10, Type.TEXT, r -> r.order().receiverZipcode()),
    RECEIVER_ADDRESS("주소", 50, Type.TEXT, r -> r.order().receiverAddress()),
    DELIVERY_MEMO("배송메모", 30, Type.TEXT, r -> r.order().deliveryMemo()),
    // 금액 (주문 레벨, 마켓 수신값)
    TOTAL_ITEM_AMOUNT("상품금액합계", 14, Type.NUMBER, r -> r.order().totalItemAmount()),
    PAID_AMOUNT("결제금액", 14, Type.NUMBER, r -> r.order().paidAmount()),
    CURRENCY("통화", 8, Type.TEXT, r -> r.order().currency());

    /** 항상 포함되는 열 (요청에 없으면 이 순서로 맨 앞) */
    private static final List<OrderDownloadColumn> REQUIRED = List.of(ORDER_NO, CHANNEL);

    enum Type { TEXT, NUMBER }

    /** 열 값의 입력 = 주문 1건 × 전개 1행 */
    public record Row(DownloadOrder order, String channelCode, DownloadLine line) {
        static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    }

    private final String header;
    private final int width;
    private final Type type;
    private final Function<Row, Object> value;

    OrderDownloadColumn(String header, int width, Type type, Function<Row, Object> value) {
        this.header = header;
        this.width = width;
        this.type = type;
        this.value = value;
    }

    /**
     * 요청 키 → 열 목록. 비었으면 전체, 중복은 처음 위치만, 필수 열이 없으면 맨 앞에 붙인다.
     *
     * @throws InvalidRequestException 모르는 키
     */
    public static List<OrderDownloadColumn> resolve(List<String> keys) {
        if (keys == null || keys.stream().allMatch(k -> k == null || k.isBlank())) {
            return List.of(values());
        }
        Set<OrderDownloadColumn> requested = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            Arrays.stream(values()).filter(c -> c.name().equals(key.trim())).findFirst()
                    .ifPresentOrElse(requested::add, () -> unknown.add(key));
        }
        if (!unknown.isEmpty()) {
            throw new InvalidRequestException("알 수 없는 다운로드 열입니다: " + String.join(", ", unknown));
        }
        List<OrderDownloadColumn> columns = new ArrayList<>(REQUIRED.stream().filter(c -> !requested.contains(c)).toList());
        columns.addAll(requested);
        return List.copyOf(columns);
    }

    public String header() {
        return header;
    }

    int width() {
        return width;
    }

    Type type() {
        return type;
    }

    Object valueOf(Row row) {
        return value.apply(row);
    }
}
