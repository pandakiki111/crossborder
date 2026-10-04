package com.crossborder.oms.controller;

import com.crossborder.oms.dto.PageResponse;
import com.crossborder.oms.dto.product.ChannelMappingChangeResponse;
import com.crossborder.oms.dto.product.ChannelMappingRequest;
import com.crossborder.oms.dto.product.ChannelMappingResponse;
import com.crossborder.oms.dto.product.ChannelMappingSearchCondition;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.product.ChannelMappingService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 매핑: 채널 상품코드(+옵션코드) ↔ 판매상품. 주문 수집·엑셀 시딩의 판매상품 확정 기준.
 * 매핑은 이력이라 재지정·삭제는 처리 시각 기준 마감으로 동작한다 (ChannelMappingService).
 */
@RestController
@RequestMapping("/api/product-mappings")
public class ChannelMappingController {

    private final ChannelMappingService channelMappingService;

    public ChannelMappingController(ChannelMappingService channelMappingService) {
        this.channelMappingService = channelMappingService;
    }

    @GetMapping
    public PageResponse<ChannelMappingResponse> search(@ModelAttribute ChannelMappingSearchCondition condition,
                                                       @PageableDefault(size = 20) Pageable pageable,
                                                       @AuthenticationPrincipal AuthenticatedUser user) {
        return PageResponse.from(channelMappingService.search(condition, pageable, user));
    }

    /**
     * 등록 후 그 코드의 매핑안됨 주문 항목을 확정한 결과를 함께 돌려준다.
     */
    @PostMapping
    public ResponseEntity<ChannelMappingChangeResponse> create(@Valid @RequestBody ChannelMappingRequest request,
                                                               @AuthenticationPrincipal AuthenticatedUser user) {
        ChannelMappingChangeResponse response = channelMappingService.create(request, user);
        return ResponseEntity.created(URI.create("/api/product-mappings/" + response.mappingId())).body(response);
    }

    /**
     * 재지정. 이력이 새 행으로 남으므로 응답의 mappingId(현재 유효 행)가 요청한 id와 다를 수 있다.
     */
    @PutMapping("/{mappingId}")
    public ChannelMappingChangeResponse update(@PathVariable Long mappingId,
                                               @Valid @RequestBody ChannelMappingRequest request,
                                               @AuthenticationPrincipal AuthenticatedUser user) {
        return channelMappingService.update(mappingId, request, user);
    }

    @DeleteMapping("/{mappingId}")
    public ResponseEntity<Void> delete(@PathVariable Long mappingId, @AuthenticationPrincipal AuthenticatedUser user) {
        channelMappingService.delete(mappingId, user);
        return ResponseEntity.noContent().build();
    }
}
