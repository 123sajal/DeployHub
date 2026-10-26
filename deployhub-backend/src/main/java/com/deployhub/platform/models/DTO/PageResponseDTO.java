package com.deployhub.platform.models.DTO;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

public record PageResponseDTO<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <E, T> PageResponseDTO<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponseDTO<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
