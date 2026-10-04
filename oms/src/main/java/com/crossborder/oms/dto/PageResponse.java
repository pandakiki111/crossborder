package com.crossborder.oms.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 페이지 응답. Spring Data Page(PageImpl)를 그대로 직렬화하면 구조가 고정되지 않아 별도 형태로 내린다.
 *
 * @param page 0부터 시작
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
