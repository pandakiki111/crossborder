package com.crossborder.oms.service.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 등록 입력. 수집 API·엑셀 시딩이 각자 채널 데이터를 이 형태로 맞춰서 넘긴다.
 * <p>
 * 금액은 모두 마켓 수신값 그대로다 (우리가 계산해서 맞추지 않는다).
 * 채널 상품코드 → 판매상품 확정은 호출 측 책임 (ChannelProductResolver).
 * 매핑이 없는 항목도 받는다 (saleProductId = null) — 주문은 매핑안됨(mappingPending)으로 등록된다.
 * <p>
 * paidAt: 결제 시각 (마켓 수신값, 없으면 null — 이벤트 PAID 기준은 주문 시각으로 판정). 엑셀 시딩은 미입력 시 orderedAt을 넣는다.
 * <p>
 * brandId: 한 주문 = 단일 브랜드. 수집 시점에 알고 있다 (엑셀 시딩은 업로드 시 지정, 수집은 스토어 계정 기준).
 * 회사는 브랜드에서 정하고, 매핑은 이 브랜드 안에서만 찾는다.
 */
public record OrderRegistrationCommand(
        Long salesChannelId,
        String channelOrderNo,
        Long brandId,
        BigDecimal totalItemAmount,
        BigDecimal paidAmount,
        String currency,
        String ordererName,
        String receiverName,
        String receiverPhone,
        String receiverZipcode,
        String receiverAddress,
        String deliveryMemo,
        LocalDateTime orderedAt,
        LocalDateTime paidAt,
        List<Item> items
) {

    /** 결제 시각 없이 (paidAt = null) */
    public OrderRegistrationCommand(Long salesChannelId, String channelOrderNo, Long brandId, BigDecimal totalItemAmount,
                                    BigDecimal paidAmount, String currency, String ordererName, String receiverName,
                                    String receiverPhone, String receiverZipcode, String receiverAddress,
                                    String deliveryMemo, LocalDateTime orderedAt, List<Item> items) {
        this(salesChannelId, channelOrderNo, brandId, totalItemAmount, paidAmount, currency, ordererName, receiverName,
                receiverPhone, receiverZipcode, receiverAddress, deliveryMemo, orderedAt, null, items);
    }

    /**
     * @param saleProductId     일반 항목: 매핑으로 확정된 판매상품. 매핑 없으면 null (매핑안됨)
     * @param giftProductId     사은품 항목: 채널 상품코드(=SKU)로 확정된 제품. 사은품은 매핑을 거치지 않는다
     * @param channelOptionCode 옵션 없음은 '' (SaleProductChannelMapping.normalizeOptionCode)
     * @param unitPrice         수신값 그대로 (사은품도)
     */
    public record Item(Long saleProductId, Long giftProductId, String channelProductCode, String channelOptionCode,
                       int quantity, BigDecimal unitPrice) {

        public static Item ofSaleProduct(Long saleProductId, String channelProductCode, String channelOptionCode,
                                         int quantity, BigDecimal unitPrice) {
            return new Item(saleProductId, null, channelProductCode, channelOptionCode, quantity, unitPrice);
        }

        public static Item ofGift(Long giftProductId, String channelProductCode, String channelOptionCode,
                                  int quantity, BigDecimal unitPrice) {
            return new Item(null, giftProductId, channelProductCode, channelOptionCode, quantity, unitPrice);
        }

        public boolean isGift() {
            return giftProductId != null;
        }

        /** 사은품은 항상 확정, 일반 항목은 판매상품이 있어야 확정 */
        public boolean isMapped() {
            return isGift() || saleProductId != null;
        }
    }
}
