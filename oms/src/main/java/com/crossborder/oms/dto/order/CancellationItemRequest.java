package com.crossborder.oms.dto.order;

/**
 * 취소할 항목과 수량. 수량이 항목의 유효 수량과 같으면 전체 취소, 작으면 수량 부분취소(행 분할).
 */
public record CancellationItemRequest(Long orderItemId, Integer quantity) {
}
