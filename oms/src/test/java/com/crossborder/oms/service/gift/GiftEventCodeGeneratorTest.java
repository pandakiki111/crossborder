package com.crossborder.oms.service.gift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GiftEventCodeGeneratorTest {

    @Test
    void 혼동_문자를_뺀_31자_알파벳이다() {
        assertThat(GiftEventCodeGenerator.ALPHABET).hasSize(31).doesNotContain("0", "O", "1", "I", "L");
        assertThat(GiftEventCodeGenerator.ALPHABET.chars().distinct().count()).isEqualTo(31);
    }

    @Test
    void GEVT_접두_8자리_총_13자이고_알파벳_전체를_쓴다() {
        Random random = new Random(7);
        Set<Character> used = new HashSet<>();
        IntStream.range(0, 2000).mapToObj(i -> GiftEventCodeGenerator.generate(random)).forEach(code -> {
            assertThat(code).hasSize(13).startsWith("GEVT-");
            code.substring(5).chars().forEach(c -> used.add((char) c));
        });
        assertThat(used).hasSize(31);
    }
}
