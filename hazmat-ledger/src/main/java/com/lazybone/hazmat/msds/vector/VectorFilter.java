package com.lazybone.hazmat.msds.vector;

/**
 * 向量存储的过滤条件。三项都为 null 时返回全部。
 *
 * <p>与检索层的 {@link com.lazybone.hazmat.msds.SearchFilter} 刻意分开：
 * 存储层还要按 {@code index_version} 过滤（B4），而检索层不应该关心版本号从哪来。
 */
public record VectorFilter(String casNo, String docType, String indexVersion) {

    public static final VectorFilter NONE = new VectorFilter(null, null, null);

    public static VectorFilter of(String casNo, String docType) {
        return new VectorFilter(casNo, docType, null);
    }

    public boolean matches(VectorRecord record) {
        if (casNo != null && !casNo.equals(record.casNo())) {
            return false;
        }
        if (docType != null && !docType.equals(record.docType())) {
            return false;
        }
        return indexVersion == null || indexVersion.equals(record.indexVersion());
    }
}
