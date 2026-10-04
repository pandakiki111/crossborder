package com.crossborder.oms.service.order.seed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
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
import org.apache.poi.ss.util.CellReference;
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
 * 업로드된 시딩 파일. 첫 시트의 1행을 헤더로 읽는다.
 * <p>
 * 읽기는 SAX 스트리밍(XSSF event API)이라 워크북 객체 모델을 메모리에 올리지 않고, 셀 값 문자열만 행 단위로 보관한다.
 * 결과 파일은 보관한 원본 값 + 처리결과 열을 SXSSF(스트리밍 쓰기)로 새로 쓴다. 원본의 모든 열·행 위치는 보존되지만
 * 서식(색·테두리)과 첫 시트 외 시트는 유지되지 않는다.
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

    private record RawRow(int rowIndex, String[] cells) {

        String cell(int index) {
            return index < cells.length ? cells[index] : null;
        }
    }

    private record Result(ResultKind kind, String message) {
    }

    private final String sheetName;
    private final RawRow header;
    private final List<RawRow> dataRows;
    private final Map<OrderSeedColumn, Integer> columnIndex;
    /** 원본 열 → 시딩 컬럼 (결과 파일에서 숫자 컬럼을 숫자 셀로 쓰기 위함) */
    private final Map<Integer, OrderSeedColumn> columnAt = new HashMap<>();
    private final int resultColumnIndex;
    private final Map<Integer, Result> results = new HashMap<>();

    private OrderSeedSheet(String sheetName, List<RawRow> rows) {
        this.sheetName = sheetName;
        if (rows.isEmpty() || rows.getFirst().rowIndex() != HEADER_ROW) {
            throw new InvalidSeedFileException("첫 시트 1행에 헤더가 없습니다.");
        }
        this.header = rows.getFirst();
        this.dataRows = rows.subList(1, rows.size());

        this.columnIndex = new EnumMap<>(OrderSeedColumn.class);
        int existingResultColumn = -1;
        for (int i = 0; i < header.cells().length; i++) {
            String value = header.cell(i);
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
        this.resultColumnIndex = existingResultColumn >= 0 ? existingResultColumn : header.cells().length;
    }

    /**
     * 첫 시트를 스트리밍으로 읽는다. xlsx만 지원한다.
     * <p>
     * 업로드를 임시 파일로 받은 뒤 파일로 연다. {@code OPCPackage.open(InputStream)}은 zip 엔트리를 전부 압축 해제해
     * 메모리에 올리므로(5만 행 시트 XML이 힙 128MB에서 OOM), 파일 기반으로 열어 필요한 엔트리만 읽게 한다.
     */
    static OrderSeedSheet read(InputStream in) {
        Path file;
        try {
            file = Files.createTempFile("order-seed-", ".xlsx");
        } catch (IOException e) {
            throw new UncheckedIOException("시딩 임시 파일을 만들 수 없습니다.", e);
        }
        try {
            try {
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new InvalidSeedFileException("업로드 파일을 읽을 수 없습니다.", e);
            }
            return read(file);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // OS 임시 디렉터리라 남아도 정리된다
            }
        }
    }

    private static OrderSeedSheet read(Path file) {
        OPCPackage pkg;
        try {
            pkg = OPCPackage.open(file.toFile(), PackageAccess.READ);
        } catch (OpenXML4JException | RuntimeException e) {
            // POI는 형식 오류를 여러 런타임 예외(NotOfficeXmlFileException 등)로 던진다
            throw new InvalidSeedFileException("엑셀(xlsx) 파일을 읽을 수 없습니다.", e);
        }
        try {
            XSSFReader reader = new XSSFReader(pkg);
            StylesTable styles = reader.getStylesTable();
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            if (!sheets.hasNext()) {
                throw new InvalidSeedFileException("시트가 없습니다.");
            }
            RowCollector collector = new RowCollector();
            String sheetName;
            try (InputStream sheet = sheets.next()) {
                sheetName = sheets.getSheetName();
                XMLReader parser = XMLHelper.newXMLReader();
                parser.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings, collector,
                        new RawValueFormatter(), false));
                parser.parse(new InputSource(sheet));
            }
            return new OrderSeedSheet(sheetName, collector.rows);
        } catch (InvalidSeedFileException e) {
            throw e;
        } catch (IOException | OpenXML4JException | SAXException | ParserConfigurationException | RuntimeException e) {
            throw new InvalidSeedFileException("엑셀(xlsx) 파일을 읽을 수 없습니다.", e);
        } finally {
            // 읽기 전용: 원본 패키지에 아무것도 쓰지 않고 닫는다
            pkg.revert();
        }
    }

    /**
     * 데이터 행을 읽어 값 형식·필수값까지 검증한다. 모든 시딩 컬럼이 빈 행은 건너뛴다.
     * 채널·매핑 존재 같은 DB 검증은 호출 측 책임.
     */
    List<OrderSeedRow> readRows() {
        List<OrderSeedRow> rows = new ArrayList<>();
        for (RawRow raw : dataRows) {
            if (columnIndex.values().stream().allMatch(i -> raw.cell(i) == null)) {
                continue;
            }
            OrderSeedRow row = new OrderSeedRow(raw.rowIndex());
            columnIndex.forEach((column, index) -> parse(row, column, raw.cell(index)));
            rows.add(row);
        }
        return rows;
    }

    void writeResult(int rowIndex, ResultKind kind, String message) {
        results.put(rowIndex, new Result(kind, message));
    }

    /**
     * 원본 값 + 처리결과 열. 숫자 컬럼(수량·금액)은 숫자 셀, 나머지는 텍스트 셀로 쓴다
     * (앞자리 0 보존, 재업로드 시 같은 값으로 읽히도록).
     */
    byte[] toBytes() {
        SXSSFWorkbook workbook = new SXSSFWorkbook(WRITE_WINDOW);
        workbook.setCompressTempFiles(true);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            Sheet sheet = workbook.createSheet(sheetName);
            int columnCount = Math.max(resultColumnIndex + 1, header.cells().length);

            Row headerRow = sheet.createRow(HEADER_ROW);
            for (int i = 0; i < columnCount; i++) {
                String value = i == resultColumnIndex ? RESULT_HEADER : header.cell(i);
                if (value != null) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(value);
                    cell.setCellStyle(styles.header);
                }
                OrderSeedColumn column = columnAt.get(i);
                sheet.setColumnWidth(i, (column != null ? OrderSeedTemplateWriter.columnWidth(column) : 14) * 256);
            }
            sheet.setColumnWidth(resultColumnIndex, 60 * 256);

            for (RawRow raw : dataRows) {
                Row row = sheet.createRow(raw.rowIndex());
                for (int i = 0; i < raw.cells().length; i++) {
                    if (i != resultColumnIndex && raw.cell(i) != null) {
                        writeValue(row.createCell(i), columnAt.get(i), raw.cell(i), styles);
                    }
                }
                Result result = results.get(raw.rowIndex());
                if (result != null) {
                    Cell cell = row.createCell(resultColumnIndex);
                    cell.setCellValue(result.message());
                    cell.setCellStyle(styles.result.get(result.kind()));
                }
            }
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
     * 행 단위로 셀 문자열을 모은다. 빈 셀은 이벤트가 오지 않으므로 셀 참조(A1)로 열 위치를 잡는다.
     */
    private static final class RowCollector implements SheetContentsHandler {

        private final List<RawRow> rows = new ArrayList<>();
        private Map<Integer, String> current;
        private int lastColumn;

        @Override
        public void startRow(int rowNum) {
            current = new HashMap<>();
            lastColumn = -1;
        }

        @Override
        public void endRow(int rowNum) {
            int width = current.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
            String[] cells = new String[width];
            current.forEach((index, value) -> cells[index] = value);
            rows.add(new RawRow(rowNum, cells));
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int column = cellReference != null ? new CellReference(cellReference).getCol() : lastColumn + 1;
            lastColumn = column;
            if (formattedValue != null && !formattedValue.isBlank()) {
                current.put(column, formattedValue.trim());
            }
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
