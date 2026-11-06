package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.entity.KnowledgeDocType;
import com.lazybone.hazmat.entity.MsdsChunk;

/**
 * 检索过滤条件：试剂与文档类型都可选，全部为空时不过滤。
 *
 * <p>用对象而不是继续往方法上加参数：调用点已经有三处，再各加一个 {@code docType}
 * 会让每个改签名的地方都要重复一遍「空值怎么归一化、非法值怎么报错」。
 *
 * <p>非法 docType 直接 400 而不是悄悄按 MSDS 处理：用户写错一个字就得到一份
 * 「看起来正常、实际过滤掉了国标」的结果，比报错更难发现。
 */
public record SearchFilter(String casNo, String docType) {

    public static final SearchFilter NONE = new SearchFilter(null, null);

    public static SearchFilter of(String casNo, String docType) {
        return new SearchFilter(blankToNull(casNo), normalizeDocType(docType));
    }

    /** 只按试剂过滤，供仍是旧签名的调用方使用 */
    public static SearchFilter byCasNo(String casNo) {
        return of(casNo, null);
    }

    public boolean matches(MsdsChunk chunk) {
        if (casNo != null && !casNo.equals(chunk.getCasNo())) {
            return false;
        }
        return docType == null || docType.equals(chunk.getDocType());
    }

    private static String normalizeDocType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return KnowledgeDocType.valueOf(value.trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            throw new BusinessException("docType 只支持 MSDS、GB、SOP");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
