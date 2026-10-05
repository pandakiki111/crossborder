package com.crossborder.oms.dto.order;

import com.crossborder.common.entity.order.OrderStatus;
import com.crossborder.common.entity.order.ShipmentStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 주문 목록 검색 조건 (GET 쿼리 파라미터 또는 POST 본문). 모두 선택값이고 지정한 조건은 AND로 묶인다.
 * <p>
 * 기간은 항상 적용된다 (미지정이면 최근 N일). 스코프(role)는 조건이 아니라 서버가 강제한다.
 *
 * @param status                 주문 상태 다중 선택 (OR)
 * @param mappingPending         true = 매핑안됨 주문만, false = 매핑완료 주문만
 * @param brandId                스코프 안에서 브랜드 선택 (항목 브랜드 기준). BRAND_STAFF는 자기 브랜드 고정이라 무시
 * @param shipmentStatus         회차 상태 다중 선택: 주문의 회차 중 하나라도 해당 상태면 포함
 * @param unsplit                true = 미분리 주문 (유효 회차 = CANCELED가 아닌 회차가 없음). shipmentStatus와 함께 주면 OR
 * @param orderNo                내부 주문번호 정확 일치
 * @param channelOrderNos        마켓 주문번호 복수 정확 일치 (엑셀 붙여넣기 → 배열, 개수 상한 설정값)
 * @param channelOrderNoContains 마켓 주문번호 단건 부분 일치 (기간 최대 설정값)
 * @param sku                    제품 SKU. 판매상품 구성·건별 사은품 어느 쪽으로든 그 제품이 들어간 주문
 * @param skuMatch               EXACT(기본) / PARTIAL (기간 최대 설정값)
 * @param orderedFrom            주문일 시작 (포함, yyyy-MM-dd)
 * @param orderedTo              주문일 끝 (포함, yyyy-MM-dd)
 */
public record OrderSearchCondition(
        List<OrderStatus> status,
        Boolean mappingPending,
        Long salesChannelId,
        Long brandId,
        List<ShipmentStatus> shipmentStatus,
        Boolean unsplit,
        String orderNo,
        List<String> channelOrderNos,
        String channelOrderNoContains,
        String sku,
        SkuMatch skuMatch,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate orderedFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate orderedTo
) {

    public enum SkuMatch {
        EXACT,
        PARTIAL
    }

    /** 조건 없음 (기간은 기본값) */
    public static OrderSearchCondition empty() {
        return new OrderSearchCondition(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
