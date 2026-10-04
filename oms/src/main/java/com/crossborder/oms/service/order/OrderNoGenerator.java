package com.crossborder.oms.service.order;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * 내부 주문번호 채번: ORD-{yyyyMMdd}-{영숫자 8자리 랜덤}
 * <p>
 * TODO: 운영 채번 규칙(일자별 시퀀스 등)이 정해지면 교체. 충돌은 uk_orders_order_no가 최종 방어한다.
 */
@Component
public class OrderNoGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final char[] ALPHANUMERIC = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int RANDOM_LENGTH = 8;

    public String generate() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder("ORD-").append(LocalDate.now().format(DATE)).append('-');
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            sb.append(ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)]);
        }
        return sb.toString();
    }
}
