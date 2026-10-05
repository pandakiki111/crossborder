package com.crossborder.oms.service.order.download;

import com.crossborder.oms.exception.InvalidRequestException;
import com.crossborder.oms.service.order.download.OrderDownloadColumn.Row;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * 다운로드 파일 SXSSF 스트리밍 쓰기. 메모리에는 최근 WRITE_WINDOW행만 두고 나머지는 압축 임시 파일로 내린다
 * (시딩 결과 파일과 같은 방식). 최종 xlsx(zip)는 {@link #writeTo}에서 출력 스트림으로 바로 쓴다 — 파일 전체를 메모리에 올리지 않는다.
 * 숫자 열(수량·금액)은 숫자 셀, 나머지는 텍스트 셀 (앞자리 0 보존).
 */
final class OrderDownloadWriter implements AutoCloseable {

    /** SXSSF가 메모리에 유지하는 행 수 */
    private static final int WRITE_WINDOW = 200;
    /** 엑셀 시트 마지막 행 번호 (헤더 포함 1,048,576행) */
    private static final int LAST_ROW_INDEX = SpreadsheetVersion.EXCEL2007.getLastRowIndex();

    private final SXSSFWorkbook workbook;
    private final Sheet sheet;
    private final List<OrderDownloadColumn> columns;
    private int rowCount;

    OrderDownloadWriter(List<OrderDownloadColumn> columns) {
        this.columns = columns;
        this.workbook = new SXSSFWorkbook(WRITE_WINDOW);
        workbook.setCompressTempFiles(true);
        this.sheet = workbook.createSheet("주문");
        CellStyle headerStyle = headerStyle(workbook);
        org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
        for (int i = 0; i < columns.size(); i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(columns.get(i).header());
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(i, columns.get(i).width() * 256);
        }
        sheet.createFreezePane(0, 1);
    }

    /**
     * @throws InvalidRequestException 엑셀 시트 행 한도 초과 (응답을 쓰기 전이라 400으로 돌려줄 수 있다)
     */
    void add(Row row) {
        if (rowCount + 1 > LAST_ROW_INDEX) {
            throw new InvalidRequestException("다운로드 행이 엑셀 한도(%,d행)를 넘습니다. 기간이나 조건을 좁혀 나눠 받으세요"
                    .formatted(LAST_ROW_INDEX));
        }
        org.apache.poi.ss.usermodel.Row sheetRow = sheet.createRow(++rowCount);
        for (int i = 0; i < columns.size(); i++) {
            OrderDownloadColumn column = columns.get(i);
            Object value = column.valueOf(row);
            if (value == null) {
                continue;
            }
            Cell cell = sheetRow.createCell(i);
            if (column.type() == OrderDownloadColumn.Type.NUMBER && value instanceof Number number) {
                cell.setCellValue(number instanceof BigDecimal decimal ? decimal.doubleValue() : number.doubleValue());
            } else {
                cell.setCellValue(value.toString());
            }
        }
    }

    /** 데이터 행 수 (헤더 제외) */
    int rowCount() {
        return rowCount;
    }

    void writeTo(OutputStream out) {
        try {
            workbook.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** SXSSF 임시 파일 삭제 (POI 5.3+는 close가 dispose를 포함) */
    @Override
    public void close() {
        try {
            workbook.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CellStyle headerStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
