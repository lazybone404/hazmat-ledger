package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.entity.MsdsChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 字面检索：内存倒排索引，按 IDF 加权打分。
 *
 * 为什么不用 SQL 的 {@code LIKE '%关键词%'}：中文没有空格，子串匹配看似能用，
 * 但词序一变就漏（「皮肤接触」对「接触皮肤」），而且扫全表。
 * 这里把文本切成「单字 + 相邻双字」，词序变化仍能命中同一批双字。
 *
 * 为什么单字也索引却不会淹没结果：单个汉字几乎出现在所有块里，IDF 接近 0；
 * 「闪点」「急救」这类双字只在少数块出现，IDF 高，自然主导排序。
 *
 * 几百块规模下暴力打分是毫秒级，不需要倒排压缩、也不需要 ANN。
 */
public final class MsdsLiteralIndex {

    /** 查询点名的试剂，其块加权。3 这个数只需要「压过其他试剂的偶然命中」，不必精细 */
    private static final double NAMED_CHEMICAL_BOOST = 3.0;

    private final List<MsdsChunk> chunks;
    private final Map<String, Set<Integer>> postings;
    private final Map<String, Double> idf;
    private final List<Map<String, Integer>> termFreqs;
    private final Map<String, String> namesByCas;
    /** 已索引的试剂 CAS 集合。用于区分「库里没有这份 MSDS」与「这次查询只是没命中」 */
    private final Set<String> indexedCasNos;

    private MsdsLiteralIndex(List<MsdsChunk> chunks,
                             Map<String, Set<Integer>> postings,
                             Map<String, Double> idf,
                             List<Map<String, Integer>> termFreqs,
                             Map<String, String> namesByCas,
                             Set<String> indexedCasNos) {
        this.chunks = chunks;
        this.postings = postings;
        this.idf = idf;
        this.termFreqs = termFreqs;
        this.namesByCas = namesByCas;
        this.indexedCasNos = indexedCasNos;
    }

    public static MsdsLiteralIndex empty() {
        return new MsdsLiteralIndex(List.of(), Map.of(), Map.of(), List.of(), Map.of(), Set.of());
    }

    public static MsdsLiteralIndex build(List<MsdsChunk> chunks) {
        return build(chunks, Map.of());
    }

    /**
     * @param namesByCas 每种试剂的名称。名称里的词在同一块内最多只计一次：
     *                   「甲醇」在 §1 里会出现四五遍（名称、别名、外文名），
     *                   不截断的话 §1 会靠堆词频把真正有答案的 §9 挤下去。
     *                   名称是块的主题标签，出现几次都还是同一个主题。
     */
    public static MsdsLiteralIndex build(List<MsdsChunk> chunks, Map<String, String> namesByCas) {
        List<MsdsChunk> snapshot = List.copyOf(chunks);
        Map<String, Set<Integer>> postings = new HashMap<>();
        List<Map<String, Integer>> termFreqs = new ArrayList<>(snapshot.size());

        for (int i = 0; i < snapshot.size(); i++) {
            MsdsChunk chunk = snapshot.get(i);
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokenize(chunk.getContent())) {
                tf.merge(token, 1, Integer::sum);
            }
            String name = namesByCas.get(chunk.getCasNo());
            if (name != null) {
                for (String token : tokenize(name)) {
                    tf.put(token, 1);
                }
            }
            termFreqs.add(tf);
            for (String token : tf.keySet()) {
                postings.computeIfAbsent(token, k -> new HashSet<>()).add(i);
            }
        }

        Map<String, Double> idf = new HashMap<>(postings.size());
        int n = snapshot.size();
        for (Map.Entry<String, Set<Integer>> entry : postings.entrySet()) {
            // 平滑 IDF：token 出现在所有块里时权重趋近 0，这正是单字该有的待遇
            idf.put(entry.getKey(), Math.log((n + 1.0) / (entry.getValue().size() + 0.5)));
        }
        Set<String> casNos = snapshot.stream()
                .map(MsdsChunk::getCasNo)
                .collect(Collectors.toUnmodifiableSet());
        return new MsdsLiteralIndex(snapshot, postings, idf, termFreqs, Map.copyOf(namesByCas), casNos);
    }

    /** 该试剂是否已索引任何块。注意：不等于「本次查询有命中」，两者要分开判断 */
    public boolean containsChemical(String casNo) {
        return casNo != null && indexedCasNos.contains(casNo);
    }

    /**
     * 索引时记录的试剂名：档案里有就用档案名，没有就从文档第 1 节取。
     *
     * 检索结果返回 CAS 号替名（「67-56-1 第 4 节」）对用户没有意义；
     * 档案缺位时仍应显示文档自带的名字。
     */
    public String nameOf(String casNo) {
        return casNo == null ? null : namesByCas.get(casNo);
    }

    public record Hit(MsdsChunk chunk, double score) {
    }

    public List<Hit> search(String query, String casNo, int topK) {
        return searchFiltered(query, SearchFilter.byCasNo(casNo), topK);
    }

    /**
     * 带完整过滤条件的检索。
     *
     * <p>不叫 {@code search} 重载：调用方传 {@code null} 时两个重载都能匹配，
     * 编译期就歧义了；换个名字比让每个调用点写 {@code (String) null} 干净。
     */
    public List<Hit> searchFiltered(String query, SearchFilter filter, int topK) {
        if (chunks.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        SearchFilter effective = filter == null ? SearchFilter.NONE : filter;
        List<String> expansions = MsdsQueryExpander.expand(query);
        String expanded = expansions.isEmpty() ? query : query + " " + String.join(" ", expansions);
        Set<String> tokens = new HashSet<>(queryTokens(expanded));
        if (tokens.isEmpty()) {
            return List.of();
        }
        Set<String> namedCas = namedCas(query);

        double[] scores = new double[chunks.size()];
        boolean matched = false;
        for (String token : tokens) {
            Set<Integer> posting = postings.get(token);
            if (posting == null) {
                continue;
            }
            double weight = idf.getOrDefault(token, 0.0);
            for (int index : posting) {
                if (!effective.matches(chunks.get(index))) {
                    continue;
                }
                // 词频用 log 抑制：同一块里说两遍比说一遍更相关，但相关性不该翻倍
                int tf = termFreqs.get(index).getOrDefault(token, 1);
                scores[index] += weight * (1 + Math.log(tf));
                matched = true;
            }
        }
        if (!matched) {
            return List.of();
        }

        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > 0) {
                double score = scores[i];
                // 用户点名了「甲醇」，「高锰酸钾溶于甲醇」这种偶然提及就不该排在正主前面
                if (namedCas.contains(chunks.get(i).getCasNo())) {
                    score *= NAMED_CHEMICAL_BOOST;
                }
                hits.add(new Hit(chunks.get(i), score));
            }
        }
        hits.sort(Comparator
                .comparingDouble(Hit::score).reversed()
                .thenComparing(h -> h.chunk().getCasNo())
                .thenComparingInt(h -> h.chunk().getSectionNo())
                .thenComparingInt(h -> h.chunk().getPartNo()));

        // 融合需要比接口返回更深的候选，内部允许到 50；最终 20 条由检索服务截断
        int limit = Math.max(1, Math.min(topK, 50));
        return hits.size() <= limit ? hits : hits.subList(0, limit);
    }

    public int size() {
        return chunks.size();
    }

    /** 查询里明确提到的试剂。档案名可能带「无水」之类的前缀，先去掉再比 */
    private Set<String> namedCas(String query) {
        if (namesByCas.isEmpty()) {
            return Set.of();
        }
        Set<String> matched = new HashSet<>();
        for (Map.Entry<String, String> entry : namesByCas.entrySet()) {
            String canonical = canonicalName(entry.getValue());
            if (canonical.length() >= 2 && query.contains(canonical)) {
                matched.add(entry.getKey());
            }
        }
        return matched;
    }

    private static String canonicalName(String name) {
        for (String prefix : new String[]{"无水", "十水"}) {
            if (name.startsWith(prefix) && name.length() - prefix.length() >= 2) {
                return name.substring(prefix.length());
            }
        }
        return name;
    }

    /**
     * 查询侧优先只用双字及以上的词。单字在中文里区分度太低——
     * 查「甲醇」时，「甲」「醇」会同时命中「甲醇」和「溶于甲醇」里的偶然提及，
     * 把无关块顶上来。只有整个查询都是单字（如「酸」）时才退回去用单字。
     */
    static List<String> queryTokens(String text) {
        List<String> tokens = tokenize(text);
        List<String> multiChar = tokens.stream().filter(token -> token.length() >= 2).toList();
        return multiChar.isEmpty() ? tokens : multiChar;
    }

    /**
     * 中文按「单字 + 相邻双字」切，英文和数字按整词切。
     * 索引侧保留单字：查询可能只给一个字，索引里没有就永远命中不了。
     */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder han = new StringBuilder();
        StringBuilder word = new StringBuilder();

        for (char ch : text.toLowerCase().toCharArray()) {
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                flushWord(word, tokens);
                han.append(ch);
            } else if (Character.isLetterOrDigit(ch)) {
                flushHan(han, tokens);
                word.append(ch);
            } else {
                flushHan(han, tokens);
                flushWord(word, tokens);
            }
        }
        flushHan(han, tokens);
        flushWord(word, tokens);
        return tokens;
    }

    private static void flushHan(StringBuilder han, List<String> tokens) {
        int length = han.length();
        if (length == 0) {
            return;
        }
        for (int i = 0; i < length; i++) {
            tokens.add(String.valueOf(han.charAt(i)));
            if (i + 1 < length) {
                tokens.add(han.substring(i, i + 2));
            }
        }
        han.setLength(0);
    }

    private static void flushWord(StringBuilder word, List<String> tokens) {
        if (word.length() >= 2) {
            tokens.add(word.toString());
        }
        word.setLength(0);
    }
}
