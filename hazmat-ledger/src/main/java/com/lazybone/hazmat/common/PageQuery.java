package com.lazybone.hazmat.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Locale;
import java.util.Set;

/**
 * 分页与排序参数的统一处理。
 *
 * 三件事必须集中在一处，否则每个接口各写一遍就会各漏一处：
 * <ol>
 *   <li><b>页大小上限</b>：不设上限时，一个 {@code size=1000000} 就能让数据库把整表读进内存。</li>
 *   <li><b>排序字段白名单</b>：{@code sort} 直接拼进 JPQL 的 order by。传个不存在的属性会抛
 *       500，传了敏感或不存在的列则暴露了 schema；只允许明确列出的字段，其余一律 400。</li>
 *   <li><b>LIKE 通配符转义</b>：用户搜「50%」时，{@code %} 必须当普通字符，而不是匹配任意串。</li>
 * </ol>
 */
public final class PageQuery {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private PageQuery() {
    }

    /**
     * 构造统一的稳定分页。
     *
     * 无论调用方传什么排序，最后都追加 {@code id}。没有唯一列做兜底时，
     * 排序值相同的行在两次查询里顺序可能不同——翻页会出现「第 1 页和第 2 页
     * 都有同一条、另一条却消失」。这是分页最常见也最难复现的坑。
     */
    public static Pageable of(int page, int size, Sort sort) {
        // 调用方已经按 id 排序时不再追加，避免出现 order by id desc, id asc 这种自相矛盾的语句
        Sort stable = sort.getOrderFor("id") != null
                ? sort
                : sort.and(Sort.by(Sort.Direction.ASC, "id"));
        return PageRequest.of(page, size, stable);
    }

    /**
     * 解析 {@code sort=createdAt,desc} 形式的排序参数。
     *
     * 只支持单字段：多字段排序用逗号表达会和「字段,方向」的语法打架，
     * 而本项目每个接口真正需要的排序一个字段加 id 兜底就够了。
     *
     * @param sort     请求里的原始值，可为 null
     * @param allowed  允许排序的属性名（实体属性，不是数据库列名）
     * @param fallback 未传 sort 时使用的默认排序
     * @throws BusinessException 字段不在白名单里、或方向不是 asc/desc
     */
    public static Sort parse(String sort, Set<String> allowed, Sort fallback) {
        if (sort == null || sort.isBlank()) {
            return fallback;
        }
        String[] parts = sort.split(",", -1);
        if (parts.length > 2) {
            throw new BusinessException("排序参数格式应为 sort=字段,asc|desc");
        }

        String field = parts[0].trim();
        if (!allowed.contains(field)) {
            throw new BusinessException("不支持按该字段排序，可用字段: " + String.join(", ", allowed));
        }

        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2 && !parts[1].isBlank()) {
            String dir = parts[1].trim().toLowerCase(Locale.ROOT);
            if (!dir.equals("asc") && !dir.equals("desc")) {
                throw new BusinessException("排序方向只能是 asc 或 desc");
            }
            direction = dir.equals("asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
        }
        return Sort.by(direction, field);
    }

    /**
     * 把关键词包成「包含」匹配的 LIKE 模式，并转义通配符。
     *
     * 反斜杠必须先转义，否则用户输入的 {@code \%} 会被后面的替换再处理一次，
     * 变成「转义了个寂寞」。
     */
    public static String likeContains(String keyword) {
        String escaped = keyword.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped.toLowerCase(Locale.ROOT) + "%";
    }

    /** 与 {@link #likeContains(String)} 配套的转义字符，传给 Criteria 的 like */
    public static final char LIKE_ESCAPE = '\\';
}
