package com.lazybone.hazmat.msds;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 极简的查询同义词扩展，只补最常见的那几种问法。
 *
 * 这里刻意不引入分词器或同义词库：一是要额外依赖，二是没有评测集就先堆词表，
 * 改坏了也看不出。当前只解决实测中确实召回不到的情况——用户问「误食」，
 * 而 MSDS 里写的是「食入」；字面路的双字匹配认不出这是同一件事。
 *
 * 每条扩展是否真的有用，要在阶段 3 的题集上对比加入前后的 Hit@5，
 * 没有提升的条目应当删掉，而不是留着「反正没坏处」。
 */
final class MsdsQueryExpander {

    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("误食", List.of("食入")),
            Map.entry("误服", List.of("食入")),
            Map.entry("吃下", List.of("食入")),
            Map.entry("吞下", List.of("食入")),
            Map.entry("沾到", List.of("皮肤接触")),
            Map.entry("溅到眼睛", List.of("眼睛接触")),
            Map.entry("着火", List.of("闪点", "易燃")),
            Map.entry("起火", List.of("闪点", "易燃"))
    );

    private MsdsQueryExpander() {
    }

    static List<String> expand(String query) {
        List<String> extra = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : SYNONYMS.entrySet()) {
            if (query.contains(entry.getKey())) {
                extra.addAll(entry.getValue());
            }
        }
        return extra;
    }
}
