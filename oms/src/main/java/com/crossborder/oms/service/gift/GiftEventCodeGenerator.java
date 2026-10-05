package com.crossborder.oms.service.gift;

import com.crossborder.common.entity.gift.GiftEvent;
import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * 이벤트 코드: GEVT- + 8자리. 운영자가 전화·메신저로 부르는 코드라 오독하기 쉬운 문자(0·O, 1·I·L)를 뺀 31자에서 고른다
 * (31^8 ≈ 8,500억 — 충돌은 드물고, 나면 등록이 UNIQUE 위반으로 재시도한다).
 */
public final class GiftEventCodeGenerator {

    /** 영대문자(I·L·O 제외 23자) + 숫자(0·1 제외 8자) = 31자 */
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 8;

    private static final RandomGenerator RANDOM = new SecureRandom();

    private GiftEventCodeGenerator() {
    }

    public static String generate() {
        return generate(RANDOM);
    }

    static String generate(RandomGenerator random) {
        StringBuilder code = new StringBuilder(GiftEvent.CODE_PREFIX);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
