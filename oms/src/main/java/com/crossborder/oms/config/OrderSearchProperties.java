package com.crossborder.oms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 주문 목록 검색 제약. 값의 근거는 README "대용량 조회 검증" (1,000만 건 실측).
 *
 * @param defaultPeriodDays      기간 미지정 시 최근 N일 (오늘 포함)
 * @param maxPeriodDays          조회 기간 최대 일수 (양끝 포함)
 * @param partialMatchMaxDays    부분 일치(주문번호·SKU) 검색의 최대 기간. 365일이면 상한 건수가 8~21초
 * @param maxPageSize            페이지 크기 상한 (초과는 400)
 * @param maxOffset              offset 상한. 넘으면 400 (조건을 좁혀 재조회)
 * @param countCap               건수 상한. 넘으면 "countCap+" 로 표시하고 정확한 건수는 세지 않는다
 * @param maxChannelOrderNos     주문번호 복수 정확 일치 개수 상한
 * @param skuSparseItemThreshold SKU 판정 임계: 해석된 판매상품·제품의 항목 수가 이하면 항목에서 출발(IN 세미조인),
 *                               넘으면 주문에서 출발(프로브)
 */
@ConfigurationProperties(prefix = "crossborder.order-search")
public record OrderSearchProperties(
        @DefaultValue("30") int defaultPeriodDays,
        @DefaultValue("366") int maxPeriodDays,
        @DefaultValue("31") int partialMatchMaxDays,
        @DefaultValue("1000") int maxPageSize,
        @DefaultValue("100000") long maxOffset,
        @DefaultValue("10000") int countCap,
        @DefaultValue("500") int maxChannelOrderNos,
        @DefaultValue("5000") int skuSparseItemThreshold
) {
}
