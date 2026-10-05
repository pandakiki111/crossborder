package com.crossborder.oms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 주문 다운로드 제약.
 *
 * @param maxDays     기간 최대 일수 (양끝 포함, 기간은 필수)
 * @param maxOrders   주문 수 상한. 넘으면 생성 전에 400 (엑셀 시트 한도 1,048,576행 보호 — 주문당 여러 행으로 전개된다)
 * @param chunkOrders 커서 순차 조회 묶음 크기 (메모리에 동시에 있는 주문 수)
 */
@ConfigurationProperties(prefix = "crossborder.order-download")
public record OrderDownloadProperties(
        @DefaultValue("31") int maxDays,
        @DefaultValue("100000") int maxOrders,
        @DefaultValue("1000") int chunkOrders
) {
}
