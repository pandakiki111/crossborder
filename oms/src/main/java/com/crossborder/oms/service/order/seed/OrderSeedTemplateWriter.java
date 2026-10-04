package com.crossborder.oms.service.order.seed;

import com.crossborder.common.entity.product.SalesChannel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 시딩 양식(xlsx) 생성.
 * <p>
 * 주문 시트: 헤더 + 예시 2건 (단품 주문 1건, 2상품+사은품 주문 1건 — 그룹핑·사은품 예시). 예시는 local R 시딩 매핑과 맞춰 두어
 * 양식을 그대로 올려도 등록되는지 확인할 수 있다.
 * 작성안내 시트: 작성 규칙, 컬럼별 필수/형식, 채널코드 목록.
 */
final class OrderSeedTemplateWriter {

    static final String ORDER_SHEET = "주문";
    static final String GUIDE_SHEET = "작성안내";

    /** OrderSeedColumn 순서와 같다 */
    private static final List<Object[]> EXAMPLE_ROWS = List.of(
            new Object[]{"RAKUTEN", "RKT-EXAMPLE-0001", "RKT-TONER", "", "N", 1, 2000, 2000, 1800, "JPY",
                    "山田 太郎", "山田 太郎", "090-1234-5678", "150-0001", "東京都渋谷区神宮前1-1-1", "置き配希望",
                    "2026-10-01 10:00:00"},
            new Object[]{"RAKUTEN", "RKT-EXAMPLE-0002", "RKT-MASK", "10P", "N", 2, 1500, 7500, 7000, "JPY",
                    "佐藤 花子", "佐藤 花子", "080-9876-5432", "530-0001", "大阪府大阪市北区梅田2-2-2", "",
                    "2026-10-01 11:30:00"},
            new Object[]{"RAKUTEN", "RKT-EXAMPLE-0002", "RKT-SET", "", "N", 1, 4500, 7500, 7000, "JPY",
                    "佐藤 花子", "佐藤 花子", "080-9876-5432", "530-0001", "大阪府大阪市北区梅田2-2-2", "",
                    "2026-10-01 11:30:00"},
            new Object[]{"RAKUTEN", "RKT-EXAMPLE-0002", "TEST-MASK-001", "", "Y", 1, 0, 7500, 7000, "JPY",
                    "佐藤 花子", "佐藤 花子", "080-9876-5432", "530-0001", "大阪府大阪市北区梅田2-2-2", "",
                    "2026-10-01 11:30:00"});

    private static final List<String> GUIDE_RULES = List.of(
            "업로드할 때 회사와 브랜드를 지정합니다. 채널상품코드는 지정한 브랜드의 상품 매핑에서만 찾습니다.",
            "1행 = 주문 항목 1개입니다. 채널코드 + 채널주문번호가 같은 행들이 한 주문으로 등록됩니다.",
            "정상가·실결제금액·통화·주문자·수취인·배송메모·주문일시는 주문 공통 정보입니다. 같은 주문의 모든 행에 같은 값을 입력하세요 (다르면 오류).",
            "금액은 마켓에서 받은 값 그대로 입력합니다. 상품단가×수량 합계와 정상가가 달라도 검증하지 않습니다.",
            "한 주문은 전부 등록되거나 전부 미처리됩니다. 한 행이라도 오류가 있으면 그 주문의 모든 행이 미처리됩니다.",
            "사은품여부가 Y인 행은 상품 매핑을 하지 않고 채널상품코드를 제품 SKU로 찾습니다. 단가는 입력값 그대로 저장됩니다.",
            "채널상품 매핑이 없는 항목도 주문은 등록되며, 주문이 '매핑안됨' 상태가 됩니다. 상품 매핑 후 처리하세요.",
            "이미 등록된 주문(같은 채널코드 + 채널주문번호)은 스킵됩니다. 결과 파일에서 실패 행만 고쳐 그 파일 그대로 다시 올리면 됩니다.",
            "전화번호·우편번호 앞자리 0이 사라지지 않도록 텍스트 서식을 유지하세요.",
            "주문 시트의 예시 행은 지우고 작성하세요.");

    private OrderSeedTemplateWriter() {
    }

    static byte[] write(List<SalesChannel> channels) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            writeOrderSheet(workbook.createSheet(ORDER_SHEET), styles);
            writeGuideSheet(workbook.createSheet(GUIDE_SHEET), styles, channels);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeOrderSheet(Sheet sheet, Styles styles) {
        OrderSeedColumn[] columns = OrderSeedColumn.values();
        Row header = sheet.createRow(0);
        for (int i = 0; i < columns.length; i++) {
            OrderSeedColumn column = columns[i];
            Cell cell = header.createCell(i);
            cell.setCellValue(column.header());
            cell.setCellStyle(column.isRequired() ? styles.requiredHeader : styles.header);
            sheet.setColumnWidth(i, columnWidth(column) * 256);
            // 텍스트·일시 컬럼은 텍스트 서식: 앞자리 0 보존, 일시가 엑셀 날짜로 자동 변환되지 않게
            if (column.getType() != OrderSeedColumn.Type.INTEGER && column.getType() != OrderSeedColumn.Type.AMOUNT) {
                sheet.setDefaultColumnStyle(i, styles.text);
            }
        }

        for (int r = 0; r < EXAMPLE_ROWS.size(); r++) {
            Object[] values = EXAMPLE_ROWS.get(r);
            Row row = sheet.createRow(r + 1);
            for (int i = 0; i < values.length; i++) {
                Cell cell = row.createCell(i);
                if (values[i] instanceof Number number) {
                    cell.setCellValue(number.doubleValue());
                } else {
                    cell.setCellValue((String) values[i]);
                    cell.setCellStyle(styles.text);
                }
            }
        }
        sheet.createFreezePane(0, 1);
    }

    private static void writeGuideSheet(Sheet sheet, Styles styles, List<SalesChannel> channels) {
        int r = 0;
        title(sheet.createRow(r++), styles, "주문 시딩 양식 작성안내");
        for (String rule : GUIDE_RULES) {
            sheet.createRow(r++).createCell(0).setCellValue("• " + rule);
        }

        r++;
        title(sheet.createRow(r++), styles, "컬럼 설명");
        tableHeader(sheet.createRow(r++), styles, "컬럼", "필수", "형식", "설명");
        for (OrderSeedColumn column : OrderSeedColumn.values()) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(column.getLabel());
            row.createCell(1).setCellValue(column.isRequired() ? "필수" : "선택");
            String format = column.getType().getDescription()
                    + (column.getMaxLength() != null ? " (최대 " + column.getMaxLength() + "자)" : "");
            row.createCell(2).setCellValue(format);
            row.createCell(3).setCellValue(column.getDescription());
        }

        r++;
        title(sheet.createRow(r++), styles, "채널코드 목록");
        tableHeader(sheet.createRow(r++), styles, "채널코드", "채널명");
        for (SalesChannel channel : channels) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(channel.getCode());
            row.createCell(1).setCellValue(channel.getName());
        }

        sheet.setColumnWidth(0, 16 * 256);
        sheet.setColumnWidth(1, 14 * 256);
        sheet.setColumnWidth(2, 30 * 256);
        sheet.setColumnWidth(3, 70 * 256);
    }

    private static void title(Row row, Styles styles, String text) {
        Cell cell = row.createCell(0);
        cell.setCellValue(text);
        cell.setCellStyle(styles.title);
    }

    private static void tableHeader(Row row, Styles styles, String... labels) {
        for (int i = 0; i < labels.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(labels[i]);
            cell.setCellStyle(styles.header);
        }
    }

    static int columnWidth(OrderSeedColumn column) {
        return switch (column) {
            case RECEIVER_ADDRESS -> 40;
            case DELIVERY_MEMO, ORDERED_AT, CHANNEL_ORDER_NO -> 22;
            case CHANNEL_PRODUCT_CODE, RECEIVER_PHONE, ORDERER_NAME, RECEIVER_NAME -> 16;
            default -> 12;
        };
    }

    private static final class Styles {

        final CellStyle header;
        final CellStyle requiredHeader;
        final CellStyle title;
        final CellStyle text;

        Styles(Workbook workbook) {
            Font bold = workbook.createFont();
            bold.setBold(true);

            header = headerStyle(workbook, bold, IndexedColors.GREY_25_PERCENT);
            requiredHeader = headerStyle(workbook, bold, IndexedColors.LIGHT_ORANGE);

            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 12);
            title = workbook.createCellStyle();
            title.setFont(titleFont);

            text = workbook.createCellStyle();
            text.setDataFormat(workbook.createDataFormat().getFormat("@"));
        }

        private static CellStyle headerStyle(Workbook workbook, Font font, IndexedColors fill) {
            CellStyle style = workbook.createCellStyle();
            style.setFont(font);
            style.setFillForegroundColor(fill.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setBorderBottom(BorderStyle.THIN);
            return style;
        }
    }
}
