package com.crossborder.oms.dto.product;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** 구성 전체 교체 (주문 이력이 없을 때만) */
public record CompositionRequest(@Valid @NotEmpty List<CompositionItemRequest> items) {
}
