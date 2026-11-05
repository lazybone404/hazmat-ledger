package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.dto.MsdsAnswer;
import com.lazybone.hazmat.dto.MsdsCitation;
import com.lazybone.hazmat.dto.MsdsHit;
import com.lazybone.hazmat.dto.MsdsSearchResult;
import com.lazybone.hazmat.msds.ai.AiUnavailableException;
import com.lazybone.hazmat.msds.ai.ChatAnswerClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /api/msds/ask}：检索 + 生成 + 引用，生成不可用时退回抽取式原文。
 *
 * <b>引用由后端生成，模型只能「引用第几条」。</b>模型输出里的 {@code [n]} 先按
 * 本次检索命中的编号校验，再把对应命中转成引用列表；一个有效编号都没有，
 * 就整段作废退回抽取式回答。这样即使模型胡说，接口也不会返回无法核对的出处。
 *
 * <b>没有依据时必须说没有。</b>检索为空、检索降级导致无法确认、模型明确表示
 * 片段未覆盖、抽取式找不到任何与问题词面对应的句子，这几种情况都返回
 * {@code answerable=false}，不生成猜测性内容。
 *
 * <b>抽取式不是「把整块正文倒出来」。</b>按问题与同义词扩展里的双字词给句子打分，
 * 每个命中最多取两句、全答案最多三句，并保留完整块作为引用。既压住长度，
 * 也让降级结果仍然可读。
 */
@Service
public class MsdsAnswerService {

    static final String NO_KNOWLEDGE = "当前 MSDS 中没有找到相关内容。";

    /** 检索有命中、但片段与问题没有任何词面对应：不能把无关原文当答案 */
    static final String NO_OVERLAP = "检索命中的片段与问题没有直接对应，无法给出可核对的回答。";

    private static final String SEARCH_DEGRADED =
            "检索当前处于降级状态，无法确认 MSDS 中是否有相关内容，请稍后重试。";

    /** 同时认半角与全角方括号，模型偶尔会输出【1】 */
    private static final Pattern CITATION = Pattern.compile("[\\[【](\\d{1,2})[\\]】]");

    /**
     * 模型拒答的常见说法。真实模型不会逐字复述提示词里的固定句，
     * 常在「没有找到」前后补「当前 MSDS 中」「任何关于治疗的内容」。
     *
     * 只认**开头就是拒答**：真实回答里也常出现「其他酸类在当前 MSDS 中
     * 没有找到相关内容」这种补充句，若按「包含」判定会把整段有效回答丢掉。
     *
     * 宾语不限于「内容」：实测模型会说「没有找到……具体数值」「未提供……地址」，
     * 这类回答同样没有依据，必须判成拒答。但若同一句里用「但/不过/然而」把答案补上
     * （「没有找到闪点数值，但 §9 给出 11 °C [1]」），那是有效回答，不能丢。
     */
    private static final Pattern REFUSAL = Pattern.compile(
            "^(?:很抱歉[，,]?\\s*)?(?:当前\\s*)?(?:MSDS|片段)[^。！？\\n]{0,20}"
                    + "(?:没有|未)(?:找到|检索到|提及|提到|涉及|提供|收录)?[^。！？\\n]{0,40}"
                    + "(?:内容|数值|信息|资料|说明|条目|字段|地址|电话|价格|库存|数据)"
                    + "(?![^。！？\\n]{0,20}(?:但|不过|然而))");

    private static final int MAX_EXTRACT_SENTENCES = 3;
    private static final int MAX_SENTENCES_PER_HIT = 2;
    private static final int MAX_SENTENCE_CHARS = 220;

    /** 去掉 Markdown 列表符号与有序编号，引用里不留「- 」 */
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*>]|\\d+[.、])\\s*");

    /** 切块时加在正文前的「[甲醇] §4 急救措施」。它是章节标题，不是可引用的句子 */
    private static final Pattern SECTION_HEADING = Pattern.compile("^\\[[^\\]]+]\\s*§\\d+");

    /**
     * 问题里的明确意图 → MSDS 固定 16 节中对应的章节。
     *
     * 只用命中顺序取句会出问题：问「误食」时，排第 1 的毒理块前两条就把名额占满，
     * 真正含急救措施的 §4 即使排在第 3 也轮不到。这里给意图章节的句子加优先级，
     * 章节对应关系是 MSDS 标准结构，不是按本项目语料硬凑的。
     */
    private static final Map<Integer, List<String>> INTENT_SECTIONS = Map.of(
            4, List.of("急救", "误食", "误服", "食入", "吞下", "吃下", "溅到", "沾到",
                    "接触", "吸入", "眼睛", "中毒", "皮肤"),
            5, List.of("灭火", "着火", "起火", "火灾", "燃烧"),
            6, List.of("泄漏", "溢出", "洒了"),
            7, List.of("储存", "贮存", "存放", "保存"),
            8, List.of("防护", "手套", "口罩", "护目镜", "呼吸器"),
            9, List.of("闪点", "沸点", "熔点", "密度", "溶解", "外观", "颜色", "气味"),
            10, List.of("稳定性", "分解", "聚合", "禁忌"),
            11, List.of("毒性", "ld50", "致癌"));

    private final MsdsSearchService searchService;
    private final ChatAnswerClient chatClient;

    public MsdsAnswerService(MsdsSearchService searchService, ChatAnswerClient chatClient) {
        this.searchService = searchService;
        this.chatClient = chatClient;
    }

    public MsdsAnswer ask(String query, String casNo, int topK) {
        MsdsSearchResult search = searchService.search(query, casNo, topK);

        if (search.hits().isEmpty()) {
            boolean degraded = search.degraded();
            return new MsdsAnswer(
                    query, casNo, false, false,
                    degraded ? SEARCH_DEGRADED : NO_KNOWLEDGE,
                    List.of(), degraded, search.degradedReason(),
                    search.indexing(), search.needsRebuild());
        }

        if (!chatClient.available()) {
            return extractive(query, casNo, search,
                    "未启用生成模型，仅返回 MSDS 原文片段");
        }

        // 只把模型实际看得到的片段交出去：超过这个范围的编号模型无从核对，
        // 后面校验引用时也必须用同一份列表，否则 topK=20 时能引用到第 6~20 条
        List<MsdsHit> promptHits = search.hits().size() <= ChatAnswerClient.MAX_HITS
                ? search.hits()
                : search.hits().subList(0, ChatAnswerClient.MAX_HITS);

        String generated;
        try {
            generated = chatClient.answer(query, promptHits);
        } catch (AiUnavailableException e) {
            return extractive(query, casNo, search,
                    "生成模型调用失败，已退回 MSDS 原文片段：" + brief(e.getMessage()));
        }

        if (isRefusal(generated)) {
            // 模型明确说片段没覆盖：这是合法拒答，不要再用抽取式硬凑。
            // 判定必须在引用校验之前——模型常会一边拒答、一边引用相邻章节
            // 解释「片段只说了什么」，若先看引用就会把拒答误判成可答。
            return new MsdsAnswer(
                    query, casNo, false, true, NO_KNOWLEDGE, List.of(),
                    search.degraded(), search.degradedReason(),
                    search.indexing(), search.needsRebuild());
        }

        List<MsdsCitation> citations = citationsFor(generated, promptHits);
        if (citations.isEmpty()) {
            return extractive(query, casNo, search,
                    "生成结果未包含有效引用，已退回 MSDS 原文片段");
        }

        return new MsdsAnswer(
                query, casNo, true, true, generated, citations,
                search.degraded(), search.degradedReason(),
                search.indexing(), search.needsRebuild());
    }

    /** 抽取式降级：只摘检索片段里的原句，不补充片段之外的内容 */
    private MsdsAnswer extractive(String query, String casNo,
                                  MsdsSearchResult search, String reason) {
        List<Extracted> sentences = pickSentences(search.hits(), query);
        if (sentences.isEmpty()) {
            // 没有任何句子与问题有词面重合，说明命中的是向量噪声或无关块。
            // 抽取式无法核对语义相关性，硬摘第一句就是把无关原文包装成答案
            return new MsdsAnswer(
                    query, casNo, false, false, NO_OVERLAP, List.of(),
                    true, merge(reason, search.degradedReason()),
                    search.indexing(), search.needsRebuild());
        }

        StringBuilder answer = new StringBuilder("根据 MSDS 原文：");
        Map<Long, Integer> indexByChunk = new LinkedHashMap<>();
        List<MsdsCitation> citations = new ArrayList<>();
        for (Extracted extracted : sentences) {
            Integer index = indexByChunk.get(extracted.hit().chunkId());
            if (index == null) {
                index = indexByChunk.size() + 1;
                indexByChunk.put(extracted.hit().chunkId(), index);
                citations.add(MsdsCitation.of(index, extracted.hit()));
            }
            answer.append('\n').append(extracted.text()).append(" [").append(index).append(']');
        }
        return new MsdsAnswer(
                query, casNo, true, false, answer.toString(), List.copyOf(citations),
                true, merge(reason, search.degradedReason()),
                search.indexing(), search.needsRebuild());
    }

    /** 模型是否在开头明确拒答；只有这种回答才允许把 answerable 判成 false */
    private static boolean isRefusal(String answer) {
        return answer != null && REFUSAL.matcher(answer.strip()).find();
    }

    /**
     * 只保留回答里出现过的编号；编号越界或一个都没有时返回空列表，
     * 由调用方退回抽取式回答。
     */
    private static List<MsdsCitation> citationsFor(String answer, List<MsdsHit> hits) {
        Map<Integer, MsdsCitation> byIndex = new LinkedHashMap<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index >= 1 && index <= hits.size() && !byIndex.containsKey(index)) {
                byIndex.put(index, MsdsCitation.of(index, hits.get(index - 1)));
            }
        }
        return List.copyOf(byIndex.values());
    }

    /**
     * 从命中里挑与问题词面最相关的句子。优先意图章节，其次高分句。
     *
     * 一个词面重合都没有时返回空列表：检索命中不等于片段能回答这个问题，
     * 调用方会据此拒答，而不是把无关原文当成答案。
     *
     * 选句是全局排序，不是按命中列表逐条取：逐条取时，前面块的句子会先把名额占满，
     * 后面块里更贴题的句子根本没机会参与比较。
     */
    private static List<Extracted> pickSentences(List<MsdsHit> hits, String query) {
        Set<String> grams = queryGrams(query);
        Set<Integer> intent = intentSections(query);
        List<Extracted> picked = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        List<Ranked> pool = new ArrayList<>();
        for (int hitIndex = 0; hitIndex < hits.size(); hitIndex++) {
            MsdsHit hit = hits.get(hitIndex);
            boolean intentHit = intent.contains(hit.sectionNo());
            for (Candidate candidate : candidates(hit, grams)) {
                // 意图章节里没有词面重合的句子也要参与：问「中毒怎么办」时，
                // §4 的急救步骤未必出现「中毒」二字，但正是要答的内容
                if (candidate.score() > 0 || intentHit) {
                    pool.add(new Ranked(hit, candidate, hitIndex, intentHit));
                }
            }
        }

        Comparator<Ranked> order = Comparator
                .comparing(Ranked::intent).reversed()
                .thenComparing(Comparator.comparingInt((Ranked r) -> r.candidate().score()).reversed())
                .thenComparingInt(Ranked::hitIndex)
                .thenComparingInt(r -> r.candidate().order());
        pool.sort(order);

        Map<Long, Integer> takenByHit = new HashMap<>();
        for (Ranked ranked : pool) {
            if (picked.size() >= MAX_EXTRACT_SENTENCES) {
                break;
            }
            Long chunkId = ranked.hit().chunkId();
            int taken = takenByHit.getOrDefault(chunkId, 0);
            if (taken >= MAX_SENTENCES_PER_HIT || !seen.add(ranked.candidate().text())) {
                continue;
            }
            picked.add(new Extracted(ranked.hit(), ranked.candidate().text()));
            takenByHit.put(chunkId, taken + 1);
        }

        return picked;
    }

    /** 问题命中的意图章节；一句话可以同时指向多个章节（如「着火 灭火」） */
    private static Set<Integer> intentSections(String query) {
        if (query == null || query.isBlank()) {
            return Set.of();
        }
        String text = query.toLowerCase();
        Set<Integer> sections = new HashSet<>();
        for (Map.Entry<Integer, List<String>> entry : INTENT_SECTIONS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (text.contains(keyword)) {
                    sections.add(entry.getKey());
                    break;
                }
            }
        }
        return sections;
    }

    private static List<Candidate> candidates(MsdsHit hit, Set<String> grams) {
        List<Candidate> result = new ArrayList<>();
        int order = 0;
        for (String line : hit.content().split("\\R")) {
            String cleaned = BULLET.matcher(line).replaceFirst("").trim();
            if (cleaned.isEmpty() || SECTION_HEADING.matcher(cleaned).find()) {
                continue;
            }
            for (String piece : cleaned.split("(?<=[。！？；])")) {
                String text = piece.trim();
                if (text.length() < 4) {
                    continue;
                }
                if (text.length() > MAX_SENTENCE_CHARS) {
                    text = text.substring(0, MAX_SENTENCE_CHARS) + "…";
                }
                result.add(new Candidate(text, order++, score(text, grams)));
            }
        }
        return result;
    }

    /** 问题与同义词扩展里的连续双字；中文没有空格，双字比整句包含更能容忍语序变化 */
    private static Set<String> queryGrams(String query) {
        Set<String> terms = new LinkedHashSet<>();
        terms.add(query);
        terms.addAll(MsdsQueryExpander.expand(query));

        Set<String> grams = new LinkedHashSet<>();
        for (String term : terms) {
            String compact = term.replaceAll("[\\s\\p{Punct}，。！？、；：（）【】《》]", "");
            if (compact.isEmpty()) {
                continue;
            }
            if (compact.length() <= 2) {
                grams.add(compact);
            } else {
                for (int i = 0; i + 1 < compact.length(); i++) {
                    grams.add(compact.substring(i, i + 2));
                }
            }
        }
        return grams;
    }

    private static int score(String sentence, Set<String> grams) {
        int score = 0;
        for (String gram : grams) {
            if (sentence.contains(gram)) {
                score++;
            }
        }
        return score;
    }

    private static String merge(String first, String second) {
        if (second == null || second.isBlank()) {
            return first;
        }
        if (first == null || first.isBlank()) {
            return second;
        }
        return first + "；" + second;
    }

    private static String brief(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        return message.length() <= 160 ? message : message.substring(0, 160) + "…";
    }

    private record Candidate(String text, int order, int score) {
    }

    private record Ranked(MsdsHit hit, Candidate candidate, int hitIndex, boolean intent) {
    }

    private record Extracted(MsdsHit hit, String text) {
    }
}
