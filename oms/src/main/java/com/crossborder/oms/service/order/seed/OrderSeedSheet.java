package com.crossborder.oms.service.order.seed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * 업로드된 시딩 파일(임시 파일)의 스트리밍 읽기·쓰기. 첫 시트의 1행을 헤더로 읽는다.
 * <p>
 * 행 데이터를 보관하지 않는다. 읽기는 SAX(XSSF event API)로 파일을 처음부터 끝까지 흘리며 행마다 콜백을 부르고,
 * 시딩은 같은 파일을 세 번 흘린다 (1차 주문 인덱스 → 2차 청크 처리 → 3차 결과 파일 쓰기).
 * 결과 파일은 3차 패스에서 원본 값 + 처리결과 열을 SXSSF(스트리밍 쓰기)로 원본 행 순서대로 쓴다.
 * 원본의 모든 열·행 위치는 보존되지만 서식(색·테두리)과 첫 시트 외 시트는 유지되지 않는다.
 * 이미 결과 열이 있는 파일(재업로드)은 그 열을 새 결과로 바꿔 쓴다 — 운영자는 실패 행만 고쳐 그 파일 그대로 다시 올리면 된다.
 */
class OrderSeedSheet {

    static final String RESULT_HEADER = "처리결과";

    private static final int HEADER_ROW = 0;
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    /** DECIMAL(12,2) */
    private static final int AMOUNT_INTEGER_DIGITS = 10;
    private static final int AMOUNT_SCALE = 2;
    /** SXSSF가 메모리에 유지하는 행 수 (나머지는 임시 파일로 내린다) */
    private static final int WRITE_WINDOW = 200;

    /** UNMAPPED = 등록은 됐지만 매핑안됨 항목이 있는 주문 */
    enum ResultKind { SUCCESS, UNMAPPED, SKIPPED, FAILED }

    /** 행 콜백. cells[i] = i열 셀 문자열 (빈 셀은 null) */
    @FunctionalInterface
    interface RowConsumer {
        void accept(int rowIndex, String[] cells);
    }

    private final Path file;
    private final String sheetName;
    private final String[] header;
    private final Map<OrderSeedColumn, Integer> columnIndex = new EnumMap<>(OrderSeedColumn.class);
    /** 원본 열 → 시딩 컬럼 (결과 파일에서 숫자 컬럼을 숫자 셀로 쓰기 위함) */
    private final Map<Integer, OrderSeedColumn> columnAt = new HashMap<>();
    private final int resultColumnIndex;
    /** 시딩 컬럼의 원본 열 위치 (빈 행 판정용) */
    private final int[] seedColumns;

    private OrderSeedSheet(Path file, String sheetName, String[] header) {
        this.file = file;
        this.sheetName = sheetName;
        this.header = header;
        int existingResultColumn = -1;
        for (int i = 0; i < header.length; i++) {
            String value = header[i];
            if (RESULT_HEADER.equals(value)) {
                existingResultColumn = i;
                continue;
            }
            int index = i;
            OrderSeedColumn.fromHeader(value).ifPresent(column -> {
                if (columnIndex.putIfAbsent(column, index) != null) {
                    throw new InvalidSeedFileException("헤더가 중복되었습니다: " + column.getLabel());
                }
                columnAt.put(index, column);
            });
        }
        List<String> missing = new ArrayList<>();
        for (OrderSeedColumn column : OrderSeedColumn.values()) {
            if (column.isRequired() && !columnIndex.containsKey(column)) {
                missing.add(column.header());
            }
        }
        if (!missing.isEmpty()) {
            throw new InvalidSeedFileException("필수 헤더가 없습니다: " + String.join(", ", missing)
                    + " (양식을 다운로드해서 사용하세요)");
        }
        this.resultColumnIndex = existingResultColumn >= 0 ? existingResultColumn : header.length;
        this.seedColumns = columnIndex.values().stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 헤더만 읽어 연다 (1행을 읽으면 파싱을 멈춘다). xlsx만 지원한다.
     */
    static OrderSeedSheet open(Path file) {
        String[][] header = new String[1][];
        String sheetName = stream(file, (rowIndex, cells) -> {
            if (rowIndex != HEADER_ROW) {
                throw new InvalidSeedFileException("첫 시트 1행에 헤더가 없습니다.");
            }
            header[0] = cells;
            throw StopParsing.INSTANCE;
        });
        if (header[0] == null) {
            throw new InvalidSeedFileException("첫 시트 1행에 헤더가 없습니다.");
        }
        return new OrderSeedSheet(file, sheetName, header[0]);
    }

    /** 헤더를 제외한 모든 행을 파일 순서대로 흘린다 */
    void forEachDataRow(RowConsumer consumer) {
        stream(file, (rowIndex, cells) -> {
            if (rowIndex != HEADER_ROW) {
                consumer.accept(rowIndex, cells);
            }
        });
    }

    /** 시딩 컬럼이 모두 빈 행 (건너뛴다) */
    boolean isBlank(String[] cells) {
        for (int index : seedColumns) {
            if (index < cells.length && cells[index] != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * (채널코드, 채널주문번호) 주문 키. 둘 중 하나라도 읽을 수 없는 행은 묶을 수 없으므로 행 단독 키 (이미 필수값·형식 오류).
     */
    String orderKey(int rowIndex, String[] cells) {
        OrderSeedRow keyRow = new OrderSeedRow(rowIndex);
        parse(keyRow, OrderSeedColumn.CHANNEL_CODE, cell(cells, columnIndex.get(OrderSeedColumn.CHANNEL_CODE)));
        parse(keyRow, OrderSeedColumn.CHANNEL_ORDER_NO, cell(cells, columnIndex.get(OrderSeedColumn.CHANNEL_ORDER_NO)));
        String channelCode = keyRow.text(OrderSeedColumn.CHANNEL_CODE);
        String channelOrderNo = keyRow.text(OrderSeedColumn.CHANNEL_ORDER_NO);
        return channelCode == null || channelOrderNo == null
                ? "#row-" + rowIndex
                : channelCode + '\u0000' + channelOrderNo;
    }

    /**
     * 데이터 행을 읽어 값 형식·필수값까지 검증한다. 채널·매핑 존재 같은 DB 검증은 호출 측 책임.
     */
    OrderSeedRow parseRow(int rowIndex, String[] cells) {
        OrderSeedRow row = new OrderSeedRow(rowIndex);
        columnIndex.forEach((column, index) -> parse(row, column, cell(cells, index)));
        return row;
    }

    /**
     * 원본을 다시 흘리며 원본 값 + 처리결과 열을 원본 행 순서대로 쓴다. 숫자 컬럼(수량·금액)은 숫자 셀,
     * 나머지는 텍스트 셀 (앞자리 0 보존, 재업로드 시 같은 값으로 읽히도록).
     */
    byte[] writeResultFile(SeedResultStore results) {
        SXSSFWorkbook workbook = new SXSSFWorkbook(WRITE_WINDOW);
        workbook.setCompressTempFiles(true);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            Sheet sheet = workbook.createSheet(sheetName);
            int columnCount = Math.max(resultColumnIndex + 1, header.length);

            Row headerRow = sheet.createRow(HEADER_ROW);
            for (int i = 0; i < columnCount; i++) {
                String value = i == resultColumnIndex ? RESULT_HEADER : cell(header, i);
                if (value != null) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(value);
                    cell.setCellStyle(styles.header);
                }
                OrderSeedColumn column = columnAt.get(i);
                sheet.setColumnWidth(i, (column != null ? OrderSeedTemplateWriter.columnWidth(column) : 14) * 256);
            }
            sheet.setColumnWidth(resultColumnIndex, 60 * 256);

            forEachDataRow((rowIndex, cells) -> {
                Row row = sheet.createRow(rowIndex);
                for (int i = 0; i < cells.length; i++) {
                    if (i != resultColumnIndex && cells[i] != null) {
                        writeValue(row.createCell(i), columnAt.get(i), cells[i], styles);
                    }
                }
                SeedResultStore.RowResult result = results.resultOf(rowIndex);
                if (result != null) {
                    Cell cell = row.createCell(resultColumnIndex);
                    cell.setCellValue(result.message());
                    cell.setCellStyle(styles.result.get(result.kind()));
                }
            });
            sheet.createFreezePane(0, 1);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            // SXSSF 임시 파일 삭제
            workbook.dispose();
        }
    }

    private static String cell(String[] cells, Integer index) {
        return index != null && index < cells.length ? cells[index] : null;
    }

    private static void writeValue(Cell cell, OrderSeedColumn column, String value, Styles styles) {
        boolean numericColumn = column != null
                && (column.getType() == OrderSeedColumn.Type.INTEGER || column.getType() == OrderSeedColumn.Type.AMOUNT);
        BigDecimal number = numericColumn ? toNumber(value) : null;
        if (number != null) {
            cell.setCellValue(number.doubleValue());
        } else {
            cell.setCellValue(value);
            cell.setCellStyle(styles.text);
        }
    }

    // ---------------------------------------------------------------- 파싱 (셀 값은 모두 문자열로 들어온다)

    private static void parse(OrderSeedRow row, OrderSeedColumn column, String raw) {
        if (raw == null) {
            if (column.isRequired()) {
                row.reject(column, column.getLabel() + ": 필수값 누락");
            }
            return;
        }
        switch (column.getType()) {
            case TEXT -> parseText(row, column, raw);
            case INTEGER -> parseInteger(row, column, raw);
            case AMOUNT -> parseAmount(row, column, raw);
            case DATETIME -> parseDateTime(row, column, raw);
            case YN -> parseYn(row, column, raw);
        }
    }

    private static void parseText(OrderSeedRow row, OrderSeedColumn column, String raw) {
        String value = raw;
        if (column == OrderSeedColumn.CHANNEL_CODE || column == OrderSeedColumn.CURRENCY) {
            value = value.toUpperCase();
        }
        if (column.getMaxLength() != null && value.length() > column.getMaxLength()) {
            row.reject(column, column.getLabel() + ": " + column.getMaxLength() + "자 초과 (" + value.length() + "자)");
            return;
        }
        if (column == OrderSeedColumn.CURRENCY && !CURRENCY.matcher(value).matches()) {
            row.reject(column, column.getLabel() + ": ISO 통화코드 3자리여야 함 (입력: " + raw + ")");
            return;
        }
        row.put(column, value);
    }

    private static void parseInteger(OrderSeedRow row, OrderSeedColumn column, String raw) {
        BigDecimal number = toNumber(raw);
        try {
            if (number == null) {
                throw new ArithmeticException();
            }
            int value = number.stripTrailingZeros().intValueExact();
            if (value <= 0) {
                throw new ArithmeticException();
            }
            row.put(column, value);
        } catch (ArithmeticException e) {
            row.reject(column, column.getLabel() + ": 1 이상의 정수여야 함 (입력: " + raw + ")");
        }
    }

    private static void parseAmount(OrderSeedRow row, OrderSeedColumn column, String raw) {
        BigDecimal number = toNumber(raw);
        if (number == null) {
            row.reject(column, column.getLabel() + ": 숫자여야 함 (입력: " + raw + ")");
            return;
        }
        BigDecimal value = number.stripTrailingZeros();
        if (value.signum() < 0) {
            row.reject(column, column.getLabel() + ": 0 이상이어야 함 (입력: " + raw + ")");
        } else if (value.scale() > AMOUNT_SCALE) {
            row.reject(column, column.getLabel() + ": 소수점 " + AMOUNT_SCALE + "자리까지 입력 가능 (입력: " + raw + ")");
        } else if (value.precision() - value.scale() > AMOUNT_INTEGER_DIGITS) {
            row.reject(column, column.getLabel() + ": 금액이 너무 큼 (입력: " + raw + ")");
        } else {
            row.put(column, value.setScale(AMOUNT_SCALE));
        }
    }

    /** 엑셀 날짜 셀은 RawValueFormatter가 이미 같은 형식 문자열로 바꿔서 넘긴다 */
    private static void parseDateTime(OrderSeedRow row, OrderSeedColumn column, String raw) {
        try {
            row.put(column, LocalDateTime.parse(raw, DATE_TIME));
        } catch (DateTimeParseException e) {
            row.reject(column, column.getLabel() + ": yyyy-MM-dd HH:mm:ss 형식이어야 함 (입력: " + raw + ")");
        }
    }

    private static void parseYn(OrderSeedRow row, OrderSeedColumn column, String raw) {
        switch (raw.toUpperCase()) {
            case "Y" -> row.put(column, Boolean.TRUE);
            case "N" -> row.put(column, Boolean.FALSE);
            default -> row.reject(column, column.getLabel() + ": Y 또는 N이어야 함 (입력: " + raw + ")");
        }
    }

    /** 천 단위 콤마를 걷어내고 해석. 해석 불가면 null */
    private static BigDecimal toNumber(String raw) {
        try {
            return new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 스트리밍 읽기

    /**
     * 첫 시트를 SAX로 흘리며 행마다 consumer를 부른다. 파일 기반 OPCPackage라 필요한 zip 엔트리만 스트림으로 읽는다.
     * consumer가 던진 예외는 형식 오류로 감싸지 않고 그대로 다시 던진다 (DB 오류 등이 "파일을 읽을 수 없음"으로 가려지지 않게).
     *
     * @return 시트 이름
     */
    private static String stream(Path file, RowConsumer consumer) {
        OPCPackage pkg;
        try {
            pkg = OPCPackage.open(file.toFile(), PackageAccess.READ);
        } catch (OpenXML4JException | RuntimeException e) {
            // POI는 형식 오류를 여러 런타임 예외(NotOfficeXmlFileException 등)로 던진다
            throw new InvalidSeedFileException("엑셀(xlsx) 파일을 읽을 수 없습니다.", e);
        }
        String sheetName = null;
        try {
            XSSFReader reader = new XSSFReader(pkg);
            StylesTable styles = reader.getStylesTable();
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            if (!sheets.hasNext()) {
                throw new InvalidSeedFileException("시트가 없습니다.");
            }
            try (InputStream sheet = sheets.next()) {
                sheetName = sheets.getSheetName();
                XMLReader parser = XMLHelper.newXMLReader();
                parser.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings, new RowCollector(consumer),
                        new RawValueFormatter(), false));
                parser.parse(new InputSource(sheet));
            }
            return sheetName;
        } catch (StopParsing e) {
            return sheetName;
        } catch (ConsumerFailure e) {
            throw e.cause;
        } catch (InvalidSeedFileException e) {
            throw e;
        } catch (IOException | OpenXML4JException | SAXException | ParserConfigurationException | RuntimeException e) {
            throw new InvalidSeedFileException("엑셀(xlsx) 파일을 읽을 수 없습니다.", e);
        } finally {
            // 읽기 전용: 원본 패키지에 아무것도 쓰지 않고 닫는다
            pkg.revert();
        }
    }

    /** 헤더만 필요할 때 파싱을 멈추는 신호 */
    private static final class StopParsing extends RuntimeException {
        static final StopParsing INSTANCE = new StopParsing();

        private StopParsing() {
            super(null, null, false, false);
        }
    }

    /** consumer 예외를 파서 밖으로 그대로 꺼내기 위한 운반체 */
    private static final class ConsumerFailure extends RuntimeException {
        final RuntimeException cause;

        ConsumerFailure(RuntimeException cause) {
            super(null, null, false, false);
            this.cause = cause;
        }
    }

    /**
     * 행 단위로 셀 문자열을 모아 consumer에 넘긴다. 빈 셀은 이벤트가 오지 않으므로 셀 참조(A1)로 열 위치를 잡는다.
     */
    private static final class RowCollector implements SheetContentsHandler {

        private final RowConsumer consumer;
        /** 행마다 재사용하는 셀 버퍼 (행당 HashMap을 만들지 않는다 — 세 패스 모두 거치는 경로라 비용이 누적된다) */
        private String[] buffer = new String[32];
        private int width;
        private int lastColumn;

        RowCollector(RowConsumer consumer) {
            this.consumer = consumer;
        }

        @Override
        public void startRow(int rowNum) {
            Arrays.fill(buffer, 0, width, null);
            width = 0;
            lastColumn = -1;
        }

        @Override
        public void endRow(int rowNum) {
            String[] cells = Arrays.copyOf(buffer, width);
            try {
                consumer.accept(rowNum, cells);
            } catch (StopParsing | InvalidSeedFileException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ConsumerFailure(e);
            }
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int column = cellReference != null ? columnOf(cellReference) : lastColumn + 1;
            lastColumn = column;
            if (formattedValue != null && !formattedValue.isBlank()) {
                if (column >= buffer.length) {
                    buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, column + 1));
                }
                buffer[column] = formattedValue.trim();
                width = Math.max(width, column + 1);
            }
        }

        /** "AB12" → 27 (0-based). CellReference 객체를 만들지 않는다 */
        private static int columnOf(String cellReference) {
            int column = 0;
            for (int i = 0; i < cellReference.length(); i++) {
                char ch = cellReference.charAt(i);
                if (ch < 'A' || ch > 'Z') {
                    break;
                }
                column = column * 26 + (ch - 'A' + 1);
            }
            return column - 1;
        }
    }

    /**
     * 숫자 셀을 표시 서식이 아닌 원래 값으로 넘긴다.
     * <ul>
     *   <li>날짜 서식 셀 → yyyy-MM-dd HH:mm:ss (엑셀이 주문일시 입력을 날짜로 자동 변환한 경우)</li>
     *   <li>그 외 숫자 → 지수 표기·통화기호·천 단위 구분 없는 원래 값 (예: ¥1,980 → 1980, 1.2E+11 → 120000000000)</li>
     * </ul>
     */
    private static final class RawValueFormatter extends DataFormatter {

        @Override
        public String formatRawCellContents(double value, int formatIndex, String formatString,
                                            boolean use1904Windowing) {
            if (DateUtil.isADateFormat(formatIndex, formatString) && DateUtil.isValidExcelDate(value)) {
                return DateUtil.getLocalDateTime(value, use1904Windowing, true).format(DATE_TIME);
            }
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }
    }

    // ---------------------------------------------------------------- 결과 파일 스타일

    private static final class Styles {

        final CellStyle header;
        final CellStyle text;
        final Map<ResultKind, CellStyle> result = new EnumMap<>(ResultKind.class);

        Styles(SXSSFWorkbook workbook) {
            Font bold = workbook.createFont();
            bold.setBold(true);
            header = workbook.createCellStyle();
            header.setFont(bold);

            text = workbook.createCellStyle();
            text.setDataFormat(workbook.createDataFormat().getFormat("@"));

            for (ResultKind kind : ResultKind.values()) {
                Font font = workbook.createFont();
                font.setColor(switch (kind) {
                    case SUCCESS -> IndexedColors.GREEN.getIndex();
                    case UNMAPPED -> IndexedColors.ORANGE.getIndex();
                    case SKIPPED -> IndexedColors.GREY_50_PERCENT.getIndex();
                    case FAILED -> IndexedColors.RED.getIndex();
                });
                CellStyle style = workbook.createCellStyle();
                style.setFont(font);
                result.put(kind, style);
            }
        }
    }
}
