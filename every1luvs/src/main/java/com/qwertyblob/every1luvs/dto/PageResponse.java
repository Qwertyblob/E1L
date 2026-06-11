package com.qwertyblob.every1luvs.dto;

import org.springframework.data.domain.Page;

import java.util.List;

// Stable, explicit pagination envelope. Returned instead of Spring's PageImpl (whose JSON
// shape is unstable and logs a serialization warning) so the client contract is fixed:
// { content, totalElements, totalPages, number, size }.
public record PageResponse<T>(
        List<T> content,
        long totalElements,
        int totalPages,
        int number,
        int size
) {
    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber(),
                page.getSize()
        );
    }
}
