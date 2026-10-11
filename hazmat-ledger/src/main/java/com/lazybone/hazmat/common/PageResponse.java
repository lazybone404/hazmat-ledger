package com.lazybone.hazmat.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 统一的分页响应。
 *
 * 直接返回 Spring Data 的 {@code Page} 也能用，但它会把 {@code Pageable}、
 * {@code Sort}、{@code pageable} 等实现细节一起序列化出去——这些字段会随
 * Spring Data 版本变化，前端却被绑在上面。这里只保留真正有用的六项。
 *
 * 字段命名沿用 Spring Data 的 {@code content / totalElements / totalPages}，
 * 前端不需要额外学习一套词汇。
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }

    /**
     * 分页里的实体要转成 DTO 时用这个，避免先 toList 再包一层。
     *
     * 注意只映射当前页的 content：总数、总页数直接取自 {@code Page}，
     * 不能拿映射后的列表长度去算——那会得到「每页最多 20 条」的错误总数。
     */
    public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
