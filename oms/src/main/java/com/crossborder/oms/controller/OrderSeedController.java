package com.crossborder.oms.controller;

import com.crossborder.oms.service.order.seed.InvalidSeedFileException;
import com.crossborder.oms.service.order.seed.OrderSeedResult;
import com.crossborder.oms.service.order.seed.OrderSeedService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 주문 엑셀 시딩 (수동 수집). 행 단위 오류는 4xx가 아니라 결과 파일의 처리결과 열로 돌려준다.
 */
@RestController
@RequestMapping("/api/orders/seed")
public class OrderSeedController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderSeedService orderSeedService;

    public OrderSeedController(OrderSeedService orderSeedService) {
        this.orderSeedService = orderSeedService;
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .headers(xlsxHeaders("order-seed-template.xlsx"))
                .body(orderSeedService.template());
    }

    /**
     * 업로드 대상 브랜드를 지정한다 (multipart 필드 brandId). 회사는 브랜드에서 정해지고, 업로드 가능 여부는 인증 사용자 소속으로 판단한다.
     * 성공 시 결과 파일을 내려주고, 응답 헤더 X-Seed-*에 주문 단위 건수를 싣는다 (팝업 표시용).
     * applyGiftEvents=true면 등록된 주문에 사은품 이벤트를 판정·증정하고 X-Seed-Gift-Grants(증정 기록 수)·
     * X-Seed-Gift-Failed(증정 실패 주문 수 — 결과 파일 해당 행에 재평가 안내)를 싣는다.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam Long brandId,
                                         @RequestParam(defaultValue = "false") boolean applyGiftEvents) {
        if (file.isEmpty()) {
            throw new InvalidSeedFileException("업로드된 파일이 비어 있습니다.");
        }
        OrderSeedResult result;
        try (InputStream in = file.getInputStream()) {
            result = orderSeedService.seed(in, brandId, applyGiftEvents);
        } catch (IOException e) {
            throw new InvalidSeedFileException("업로드 파일을 읽을 수 없습니다.", e);
        }
        String fileName = "order-seed-result-" + LocalDateTime.now().format(FILE_TIMESTAMP) + ".xlsx";
        return ResponseEntity.ok()
                .headers(xlsxHeaders(fileName))
                .header("X-Seed-Total", String.valueOf(result.totalCount()))
                .header("X-Seed-Success", String.valueOf(result.successCount()))
                .header("X-Seed-Unmapped", String.valueOf(result.unmappedCount()))
                .header("X-Seed-Skipped", String.valueOf(result.skippedCount()))
                .header("X-Seed-Failed", String.valueOf(result.failedCount()))
                .header("X-Seed-Gift-Grants", String.valueOf(result.giftGrants()))
                .header("X-Seed-Gift-Failed", String.valueOf(result.giftFailedCount()))
                .body(result.file());
    }

    private static HttpHeaders xlsxHeaders(String fileName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(XLSX);
        headers.setContentDisposition(ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build());
        return headers;
    }
}
