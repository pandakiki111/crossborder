package com.crossborder.oms.controller;

import com.crossborder.oms.dto.gift.GiftEventRequest;
import com.crossborder.oms.dto.gift.GiftEventResponse;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.service.gift.GiftEventService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사은품 이벤트 관리 (브랜드 스코프). 지급 이력이 생기면 수정·삭제는 409 — 중단·재시작은 계속 가능.
 */
@RestController
@RequestMapping("/api/gift-events")
public class GiftEventController {

    private final GiftEventService giftEventService;

    public GiftEventController(GiftEventService giftEventService) {
        this.giftEventService = giftEventService;
    }

    @PostMapping
    public ResponseEntity<GiftEventResponse> create(@Valid @RequestBody GiftEventRequest request,
                                                    @AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(giftEventService.create(request, user));
    }

    /** brandId 없으면 스코프 안 전체 */
    @GetMapping
    public List<GiftEventResponse> list(@RequestParam(required = false) Long brandId,
                                        @AuthenticationPrincipal AuthenticatedUser user) {
        return giftEventService.list(brandId, user);
    }

    @GetMapping("/{eventId}")
    public GiftEventResponse get(@PathVariable Long eventId, @AuthenticationPrincipal AuthenticatedUser user) {
        return giftEventService.get(eventId, user);
    }

    /** 설정을 운영자 문장으로 (응답 preview와 같은 값) */
    @GetMapping("/{eventId}/preview")
    public Map<String, String> preview(@PathVariable Long eventId, @AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("text", giftEventService.preview(eventId, user));
    }

    @PutMapping("/{eventId}")
    public GiftEventResponse update(@PathVariable Long eventId, @Valid @RequestBody GiftEventRequest request,
                                    @AuthenticationPrincipal AuthenticatedUser user) {
        return giftEventService.update(eventId, request, user);
    }

    @DeleteMapping("/{eventId}")
    public ResponseEntity<Void> delete(@PathVariable Long eventId, @AuthenticationPrincipal AuthenticatedUser user) {
        giftEventService.delete(eventId, user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{eventId}/stop")
    public GiftEventResponse stop(@PathVariable Long eventId, @AuthenticationPrincipal AuthenticatedUser user) {
        return giftEventService.stop(eventId, user);
    }

    @PostMapping("/{eventId}/restart")
    public GiftEventResponse restart(@PathVariable Long eventId, @AuthenticationPrincipal AuthenticatedUser user) {
        return giftEventService.restart(eventId, user);
    }
}
