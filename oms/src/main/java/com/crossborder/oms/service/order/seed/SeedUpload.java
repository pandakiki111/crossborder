package com.crossborder.oms.service.order.seed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 업로드를 받아 둔 임시 파일. 시딩은 이 파일을 세 번 스트리밍으로 읽는다 (인덱스 → 청크 처리 → 결과 파일 쓰기).
 * <p>
 * 파일로 받는 이유: OPCPackage.open(InputStream)은 zip 엔트리를 전부 압축 해제해 메모리에 올린다 (5만 행 힙 128MB OOM).
 * 수명은 시딩 전체 — try-with-resources로 감싸 어느 패스에서 예외가 나도 close()에서 삭제된다.
 */
final class SeedUpload implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SeedUpload.class);

    private final Path file;

    private SeedUpload(Path file) {
        this.file = file;
    }

    static SeedUpload save(InputStream in) {
        Path file;
        try {
            file = Files.createTempFile("order-seed-", ".xlsx");
        } catch (IOException e) {
            throw new UncheckedIOException("시딩 임시 파일을 만들 수 없습니다.", e);
        }
        SeedUpload upload = new SeedUpload(file);
        try {
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            return upload;
        } catch (IOException e) {
            upload.close();
            throw new InvalidSeedFileException("업로드 파일을 읽을 수 없습니다.", e);
        }
    }

    Path path() {
        return file;
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // OS 임시 디렉터리라 남아도 정리된다
            log.warn("시딩 임시 파일 삭제 실패: {}", file, e);
        }
    }
}
