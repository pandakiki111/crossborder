package com.crossborder.oms.service.order.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crossborder.common.entity.organization.UserRole;
import com.crossborder.oms.security.AuthenticatedUser;
import com.crossborder.oms.support.IntegrationTestBase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 주문 엑셀 시딩 기능 회귀 테스트 (실제 MariaDB + Redis). 결과 파일의 처리결과 열·건수·등록 결과를 검증한다.
 * <p>
 * 테스트브랜드 구성: 판매상품 SET(토너), 채널 QOO10 매핑 Q-SET-{suffix}. 사은품 SKU = MASK 제품.
 */
class OrderSeedServiceTest extends IntegrationTestBase {

    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, UserRole.ADMIN, null, null);

    @Autowired
    private OrderSeedService orderSeedService;

    private long brandId;
    private long toner;
    private String setCode;
    private String maskSku;

    @BeforeEach
    void setUp() {
        brandId = brand(company("시딩상사"), "시딩브랜드");
        toner = product(brandId, "TONER");
        long mask = product(brandId, "MASK");
        maskSku = "MASK-" + suffix;
        long setProduct = saleProduct(brandId, "SET");
        composition(setProduct, toner, 1, false);
        setCode = "Q-SET-" + suffix;
        jdbc.update("""
                INSERT INTO sale_product_channel_mappings (sale_product_id, brand_id, channel_id, code, option_code, created_user_id)
                VALUES (?, ?, ?, ?, '', 1)
                """, setProduct, brandId, channelId("QOO10"), setCode);
        login(ADMIN);
    }

    @Test
    void 성공_매핑안됨_사은품_행을_등록하고_재업로드는_전부_스킵한다() {
        Sheet input = sheet(
                row("A", setCode, "N", 2),
                row("A", setCode, "N", 1),
                row("B", "Q-NONE-" + suffix, "N", 1),
                row("B", setCode, "N", 1),
                row("C", maskSku, "Y", 1));
        Upload first = upload(input);

        assertThat(first.result().successCount()).isEqualTo(2); // A, C
        assertThat(first.result().unmappedCount()).isEqualTo(1); // B
        assertThat(first.result().failedCount()).isZero();
        assertThat(first.messages().get(1)).startsWith("성공: ");
        assertThat(first.messages().get(2)).isEqualTo(first.messages().get(1)); // 같은 주문 = 같은 주문번호
        assertThat(first.messages().get(3)).startsWith("성공(매핑안됨): ").contains("선택한 브랜드에 등록된 매핑이 없습니다");
        assertThat(first.messages().get(4)).startsWith("성공(매핑안됨): ").doesNotContain(" - ");
        assertThat(first.messages().get(5)).startsWith("성공: ");
        assertThat(itemCount("A")).isEqualTo(2);
        assertThat(allocated(toner)).isEqualTo(3); // A만 할당 (B는 매핑안됨)

        Upload second = upload(input);
        assertThat(second.result().skippedCount()).isEqualTo(3);
        assertThat(second.messages().values()).allMatch(m -> m.equals("스킵: 이미 등록된 주문"));
    }

    @Test
    void 한_행이_실패하면_그_주문의_모든_행이_실패로_표기되고_원인_행에만_사유가_붙는다() {
        Upload upload = upload(sheet(
                row("D", setCode, "N", 1),
                row("D", setCode, "N", 0),
                row("E", setCode, "N", 1),
                rowWith("E", setCode, Map.of(OrderSeedColumn.RECEIVER_NAME, "다른수취인")),
                row("F", setCode, "N", 1)));

        assertThat(upload.result().failedCount()).isEqualTo(2);
        assertThat(upload.result().successCount()).isEqualTo(1);
        assertThat(upload.messages().get(1)).isEqualTo("실패: " + OrderSeedService.SIBLING_FAILED);
        assertThat(upload.messages().get(2)).startsWith("실패: 수량");
        assertThat(upload.messages().get(3)).isEqualTo("실패: " + OrderSeedService.SIBLING_FAILED);
        assertThat(upload.messages().get(4)).contains("주문 공통정보가 첫 행(4행)과 다름: 수취인명");
        assertThat(orderCount("D")).isZero();
        assertThat(orderCount("E")).isZero();
    }

    @Test
    void 떨어진_같은_주문의_행은_한_주문으로_등록되고_빈_행은_건너뛴다() {
        List<Map<OrderSeedColumn, Object>> rows = new ArrayList<>();
        rows.add(row("G", setCode, "N", 1));
        rows.add(row("H", setCode, "N", 1));
        rows.add(new EnumMap<>(OrderSeedColumn.class)); // 빈 행
        rows.add(row("I", setCode, "N", 1));
        rows.add(row("G", setCode, "N", 2));
        Upload upload = upload(sheet(rows.toArray(Map[]::new)));

        assertThat(upload.result().totalCount()).isEqualTo(3);
        assertThat(itemCount("G")).isEqualTo(2);
        assertThat(upload.messages().get(5)).isEqualTo(upload.messages().get(1));
        assertThat(upload.messages()).doesNotContainKey(3);
    }

    @Test
    void 처리결과_열이_있는_파일을_다시_올리면_그_열을_새_결과로_바꿔_쓴다() {
        Sheet input = sheet(row("J", setCode, "N", 1));
        int resultColumn = input.getRow(0).getLastCellNum();
        input.getRow(0).createCell(resultColumn).setCellValue(OrderSeedSheet.RESULT_HEADER);
        input.getRow(1).createCell(resultColumn).setCellValue("실패: 지난번 사유");

        Upload upload = upload(input);

        assertThat(upload.headers()).containsOnlyOnce(OrderSeedSheet.RESULT_HEADER);
        assertThat(upload.messages().get(1)).startsWith("성공: ");
    }

    // ------------------------------------------------------------------ 청크 파이프라인

    /**
     * 같은 구성의 파일을 청크 크기만 바꿔 처리해도 행별 결과·주문 구성이 같아야 한다.
     * C는 4행과 9행에 떨어져 있어 청크 크기가 작으면 다른 주문들보다 늦게 완성되고 청크 경계를 넘는다.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 1000})
    void 청크_크기와_무관하게_행별_결과와_주문_구성이_같다(int chunkOrders) {
        Object target = AopTestUtils.getTargetObject(orderSeedService);
        Object original = ReflectionTestUtils.getField(target, "chunkOrders");
        ReflectionTestUtils.setField(target, "chunkOrders", chunkOrders);
        try {
            Upload upload = upload(sheet(
                    row("A", setCode, "N", 1),              // 1
                    row("A", setCode, "N", 1),              // 2
                    row("B", setCode, "N", 1),              // 3
                    row("C", setCode, "N", 1),              // 4  ┐ 떨어진 같은 주문
                    row("D", setCode, "N", 0),              // 5  실패 원인
                    row("E", "Q-NONE-" + suffix, "N", 1),   // 6  매핑안됨
                    row("F", setCode, "N", 1),              // 7
                    row("F", setCode, "N", 1),              // 8
                    row("C", setCode, "N", 2),              // 9  ┘
                    row("D", setCode, "N", 1),              // 10 형제 실패
                    row("G", maskSku, "Y", 1)));            // 11 사은품

            assertThat(upload.result().successCount()).isEqualTo(5); // A B C F G
            assertThat(upload.result().unmappedCount()).isEqualTo(1);
            assertThat(upload.result().failedCount()).isEqualTo(1);
            assertThat(upload.messages()).hasSize(11);
            assertThat(upload.messages().get(9)).isEqualTo(upload.messages().get(4)).startsWith("성공: ");
            assertThat(upload.messages().get(2)).isEqualTo(upload.messages().get(1));
            assertThat(upload.messages().get(8)).isEqualTo(upload.messages().get(7));
            assertThat(upload.messages().get(5)).startsWith("실패: 수량");
            assertThat(upload.messages().get(10)).isEqualTo("실패: " + OrderSeedService.SIBLING_FAILED);
            assertThat(upload.messages().get(6)).startsWith("성공(매핑안됨): ").contains("선택한 브랜드에 등록된 매핑이 없습니다");
            assertThat(upload.messages().get(11)).startsWith("성공: ");
            assertThat(itemCount("C")).isEqualTo(2);
            assertThat(itemCount("F")).isEqualTo(2);
            assertThat(orderCount("D")).isZero();
            // A 2 + B 1 + C 3 + F 2 = 8 (E는 매핑안됨이라 미할당, G는 마스크)
            assertThat(allocated(toner)).isEqualTo(8);
        } finally {
            ReflectionTestUtils.setField(target, "chunkOrders", original);
        }
    }

    @Test
    void 처리_중_예외가_나도_업로드_임시_파일이_남지_않는다() throws IOException {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long before = seedTempFiles(tmp);

        assertThatThrownBy(() -> upload(sheet())) // 헤더만 있는 파일 → 1차 패스 후 "데이터 행이 없습니다"
                .isInstanceOf(InvalidSeedFileException.class);

        assertThat(seedTempFiles(tmp)).isEqualTo(before);
    }

    private static long seedTempFiles(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().startsWith("order-seed-")).count();
        }
    }

    // ------------------------------------------------------------------ fixtures

    record Upload(OrderSeedResult result, List<String> headers, Map<Integer, String> messages) {
    }

    /** 결과 파일을 읽어 행 인덱스 → 처리결과 문자열 */
    Upload upload(Sheet input) {
        OrderSeedResult result = orderSeedService.seed(new ByteArrayInputStream(bytes(input.getWorkbook())), brandId);
        try (Workbook out = new XSSFWorkbook(new ByteArrayInputStream(result.file()))) {
            Sheet sheet = out.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            List<String> headers = new ArrayList<>();
            sheet.getRow(0).forEach(cell -> headers.add(formatter.formatCellValue(cell)));
            int resultColumn = headers.indexOf(OrderSeedSheet.RESULT_HEADER);
            Map<Integer, String> messages = new LinkedHashMap<>();
            for (Row row : sheet) {
                Cell cell = row.getRowNum() == 0 ? null : row.getCell(resultColumn);
                if (cell != null) {
                    messages.put(row.getRowNum(), formatter.formatCellValue(cell));
                }
            }
            return new Upload(result, headers, messages);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    Map<OrderSeedColumn, Object> row(String order, String code, String gift, int quantity) {
        Map<OrderSeedColumn, Object> values = new EnumMap<>(OrderSeedColumn.class);
        values.put(OrderSeedColumn.CHANNEL_CODE, "QOO10");
        values.put(OrderSeedColumn.CHANNEL_ORDER_NO, suffix + "-" + order);
        values.put(OrderSeedColumn.CHANNEL_PRODUCT_CODE, code);
        values.put(OrderSeedColumn.GIFT, gift);
        values.put(OrderSeedColumn.QUANTITY, String.valueOf(quantity));
        values.put(OrderSeedColumn.UNIT_PRICE, "Y".equals(gift) ? "0" : "1000");
        values.put(OrderSeedColumn.TOTAL_ITEM_AMOUNT, "5000");
        values.put(OrderSeedColumn.PAID_AMOUNT, "5000");
        values.put(OrderSeedColumn.ORDERER_NAME, "주문자");
        values.put(OrderSeedColumn.RECEIVER_NAME, "수취인");
        values.put(OrderSeedColumn.RECEIVER_ADDRESS, "주소");
        values.put(OrderSeedColumn.ORDERED_AT, "2026-10-01 10:00:00");
        return values;
    }

    Map<OrderSeedColumn, Object> rowWith(String order, String code, Map<OrderSeedColumn, Object> overrides) {
        Map<OrderSeedColumn, Object> values = row(order, code, "N", 1);
        values.putAll(overrides);
        return values;
    }

    @SafeVarargs
    static Sheet sheet(Map<OrderSeedColumn, Object>... rows) {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("주문");
        OrderSeedColumn[] columns = OrderSeedColumn.values();
        Row header = sheet.createRow(0);
        for (int c = 0; c < columns.length; c++) {
            header.createCell(c).setCellValue(columns[c].header());
        }
        for (int r = 0; r < rows.length; r++) {
            Row row = sheet.createRow(r + 1);
            for (int c = 0; c < columns.length; c++) {
                Object value = rows[r].get(columns[c]);
                if (value != null) {
                    row.createCell(c).setCellValue(value.toString());
                }
            }
        }
        return sheet;
    }

    static byte[] bytes(Workbook workbook) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    int itemCount(String order) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM order_items i JOIN orders o ON o.id = i.order_id WHERE o.channel_order_no = ?
                """, Integer.class, suffix + "-" + order);
    }

    int orderCount(String order) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE channel_order_no = ?", Integer.class,
                suffix + "-" + order);
    }
}
