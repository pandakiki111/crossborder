package com.crossborder.oms.dto.order;

import com.crossborder.common.entity.order.SplitReason;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 주문 분리. 회차 목록 = 분리 결과 그대로 (분리 미리보기 응답을 그대로 보내도 된다).
 */
public record OrderSplitRequest(
        @NotEmpty @Valid List<Round> shipments
) {

    /**
     * @param splitReason 비우면 MANUAL
     */
    public record Round(
            SplitReason splitReason,
            @NotEmpty @Valid List<Line> items
    ) {
    }

    public record Line(
            @NotNull Long orderItemId,
            @Positive int quantity
    ) {
    }
}
