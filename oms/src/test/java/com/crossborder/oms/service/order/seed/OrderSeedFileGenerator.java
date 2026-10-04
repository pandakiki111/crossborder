package com.crossborder.oms.service.order.seed;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * 성능 측정용 주문 시딩 엑셀 생성기. 로컬 테스트 데이터(R__seed_local_test_data.sql)의 테스트브랜드 매핑·SKU를 쓴다.
 * <p>
 * 실행: {@code ./gradlew :oms:generateSeedFile -Prows=10000 [-Pprefix=P1] [-Pout=build/seed/orders-10000.xlsx]}
 * <ul>
 *   <li>주문당 항목 1~3행 (평균 약 2행) → 주문 수 ≈ 행 수 / 2</li>
 *   <li>주문의 5%는 매핑 없는 상품코드 1행 포함 → 매핑안됨(mapping_pending) 경로</li>
 *   <li>주문의 10%는 사은품 행(SKU TEST-MASK-001, 단가 0) 포함</li>
 *   <li>채널주문번호는 prefix로 구분 — 같은 prefix로 다시 올리면 전부 "이미 등록된 주문"으로 스킵된다</li>
 * </ul>
 * 난수 시드 고정이라 같은 인자면 같은 파일이 나온다.
 */
public final class OrderSeedFileGenerator {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final LocalDateTime ORDERED_FROM = LocalDateTime.of(2026, 9, 1, 0, 0, 0);

    private record Item(String code, String optionCode, BigDecimal unitPrice) {
    }

    private static final Map<String, List<Item>> MAPPED_ITEMS = Map.of(
            "RAKUTEN", List.of(
                    new Item("RKT-TONER", "", new BigDecimal("2000")),
                    new Item("RKT-CREAM", "", new BigDecimal("3000")),
                    new Item("RKT-MASK", "10P", new BigDecimal("1800")),
                    new Item("RKT-SET", "", new BigDecimal("4500"))),
            "QOO10", List.of(
                    new Item("Q-TONER", "", new BigDecimal("1900")),
                    new Item("Q-MASK", "10P", new BigDecimal("1700"))));
    private static final List<String> CHANNELS = List.of("RAKUTEN", "QOO10");
    private static final String GIFT_SKU = "TEST-MASK-001";

    private OrderSeedFileGenerator() {
    }

    public static void main(String[] args) throws IOException {
        int rows = Integer.parseInt(args[0]);
        String prefix = args[1];
        Path out = Path.of(args[2]);
        Files.createDirectories(out.toAbsolutePath().getParent());

        Random random = new Random(42);
        int written = 0;
        int orders = 0;
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(500); OutputStream os = Files.newOutputStream(out)) {
            Sheet sheet = workbook.createSheet(OrderSeedTemplateWriter.ORDER_SHEET);
            Row header = sheet.createRow(0);
            OrderSeedColumn[] columns = OrderSeedColumn.values();
            for (int c = 0; c < columns.length; c++) {
                header.createCell(c).setCellValue(columns[c].header());
            }
            while (written < rows) {
                List<Map<OrderSeedColumn, String>> orderRows = order(random, prefix, ++orders);
                for (Map<OrderSeedColumn, String> values : orderRows) {
                    if (written == rows) {
                        break;
                    }
                    Row row = sheet.createRow(++written);
                    for (int c = 0; c < columns.length; c++) {
                        String value = values.get(columns[c]);
                        if (value != null) {
                            row.createCell(c).setCellValue(value);
                        }
                    }
                }
            }
            workbook.write(os);
        }
        System.out.printf("생성: %s (행 %d, 주문 약 %d)%n", out, written, orders);
    }

    private static List<Map<OrderSeedColumn, String>> order(Random random, String prefix, int seq) {
        String channel = CHANNELS.get(random.nextInt(CHANNELS.size()));
        List<Item> candidates = MAPPED_ITEMS.get(channel);
        List<Item> items = new ArrayList<>();
        int itemCount = 1 + random.nextInt(3);
        for (int i = 0; i < itemCount; i++) {
            items.add(candidates.get(random.nextInt(candidates.size())));
        }
        if (random.nextInt(100) < 5) {
            items.set(0, new Item(channel.substring(0, 1) + "-UNMAPPED-" + random.nextInt(50), "",
                    new BigDecimal("2500")));
        }
        boolean withGift = random.nextInt(100) < 10;

        BigDecimal total = BigDecimal.ZERO;
        List<Integer> quantities = new ArrayList<>();
        for (Item item : items) {
            int quantity = 1 + random.nextInt(3);
            quantities.add(quantity);
            total = total.add(item.unitPrice().multiply(BigDecimal.valueOf(quantity)));
        }
        BigDecimal paid = total.subtract(BigDecimal.valueOf(random.nextInt(5) * 100L)).max(BigDecimal.ZERO);

        Map<OrderSeedColumn, String> common = new EnumMap<>(OrderSeedColumn.class);
        common.put(OrderSeedColumn.CHANNEL_CODE, channel);
        common.put(OrderSeedColumn.CHANNEL_ORDER_NO, prefix + "-" + seq);
        common.put(OrderSeedColumn.TOTAL_ITEM_AMOUNT, total.toPlainString());
        common.put(OrderSeedColumn.PAID_AMOUNT, paid.toPlainString());
        common.put(OrderSeedColumn.CURRENCY, "JPY");
        common.put(OrderSeedColumn.ORDERER_NAME, "주문자" + seq);
        common.put(OrderSeedColumn.RECEIVER_NAME, "수취인" + seq);
        common.put(OrderSeedColumn.RECEIVER_PHONE, "090-0000-" + String.format("%04d", seq % 10000));
        common.put(OrderSeedColumn.RECEIVER_ZIPCODE, "150-0001");
        common.put(OrderSeedColumn.RECEIVER_ADDRESS, "東京都渋谷区神宮前 1-" + (seq % 100));
        common.put(OrderSeedColumn.ORDERED_AT,
                ORDERED_FROM.plusSeconds(random.nextInt(30 * 24 * 3600)).format(DATETIME));

        List<Map<OrderSeedColumn, String>> rows = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<OrderSeedColumn, String> row = new EnumMap<>(common);
            row.put(OrderSeedColumn.CHANNEL_PRODUCT_CODE, items.get(i).code());
            row.put(OrderSeedColumn.OPTION_CODE, items.get(i).optionCode());
            row.put(OrderSeedColumn.GIFT, "N");
            row.put(OrderSeedColumn.QUANTITY, String.valueOf(quantities.get(i)));
            row.put(OrderSeedColumn.UNIT_PRICE, items.get(i).unitPrice().toPlainString());
            rows.add(row);
        }
        if (withGift) {
            Map<OrderSeedColumn, String> gift = new EnumMap<>(common);
            gift.put(OrderSeedColumn.CHANNEL_PRODUCT_CODE, GIFT_SKU);
            gift.put(OrderSeedColumn.GIFT, "Y");
            gift.put(OrderSeedColumn.QUANTITY, "1");
            gift.put(OrderSeedColumn.UNIT_PRICE, "0");
            rows.add(gift);
        }
        return rows;
    }
}
