package com.lazybone.hazmat.msds;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一份 MSDS Markdown 切成检索块。
 *
 * 切法按 GB/T 16483 的 16 节来，不按固定字数切。原因很直接：
 * 用户问「误食了怎么办」时，答案整段都在第 4 节里；按 200 字硬切会把「急救措施」
 * 和后面的「消防措施」切进同一块，检索出来一半内容答非所问。
 *
 * 单节超过 {@link #MAX_CHARS} 时才继续按段落累积切分，且**不切断段落、不拆表格**。
 * 400 这个数是从 bge-small 一类 512 token 的小模型倒推的（中文约 1 token ≈ 1.5 字），
 * 换 embedding 模型必须重算，不能照抄。
 */
public final class MsdsChunker {

    /**
     * 切块规则版本。规则一改，同样内容的 hash 不变但块的语义变了，
     * 旧向量就不能再用，必须靠这个版本号让整批向量失效。
     */
    public static final int CHUNK_VERSION = 1;

    /** 单块字符上限。见类注释里对 400 的推导 */
    static final int MAX_CHARS = 400;

    private static final Pattern HEADING = Pattern.compile("^##\\s+(\\d+)\\.\\s+(.+?)\\s*$");

    private MsdsChunker() {
    }

    /** 一个检索块。content 里带「§章节号 标题」前缀，脱离文档后仍知道自己在说什么 */
    public record Chunk(int sectionNo, String sectionTitle, int partNo, String content, String textHash) {
    }

    public static List<Chunk> split(String markdown) {
        return split(markdown, MAX_CHARS);
    }

    /**
     * 指定单块上限的切分，只给对照实验（{@link #MAX_CHARS} 200/400/800 扫描）用。
     *
     * 生产路径固定走 {@link #split(String)}：这个重载不参与配置，
     * 也不会改变 {@link #CHUNK_VERSION}。真要把上限做成可配置项时，
     * 必须把上限写进四元组或升版本——否则同一份文本在不同上限下
     * 会生成不同的块与 hash，旧向量却没有失效。
     */
    public static List<Chunk> split(String markdown, int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars 必须为正数: " + maxChars);
        }
        List<Chunk> chunks = new ArrayList<>();
        int sectionNo = -1;
        String sectionTitle = null;
        StringBuilder body = new StringBuilder();

        for (String line : markdown.split("\n", -1)) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                flush(chunks, sectionNo, sectionTitle, body.toString());
                sectionNo = Integer.parseInt(m.group(1));
                sectionTitle = m.group(2).trim();
                body.setLength(0);
            } else if (sectionNo > 0) {
                // 第一个二级标题之前的内容（文件名、来源声明）不进索引：
                // 那是元信息，检索不到正文反而会污染结果
                body.append(line).append('\n');
            }
        }
        flush(chunks, sectionNo, sectionTitle, body.toString());
        return chunks;
    }

    private static void flush(List<Chunk> chunks, int sectionNo, String title, String body) {
        if (sectionNo <= 0 || title == null) {
            return;
        }
        String prefix = "§" + sectionNo + " " + title + "\n";
        StringBuilder current = new StringBuilder(prefix);
        int partNo = 0;

        for (String paragraph : splitParagraphs(body)) {
            // 超过上限就先把已累积的段落封块，避免把两节内容揉进一块
            if (current.length() > prefix.length()
                    && current.length() + paragraph.length() + 1 > MAX_CHARS) {
                chunks.add(new Chunk(sectionNo, title, partNo++, current.toString(), sha256(current.toString())));
                current = new StringBuilder(prefix);
            }
            current.append(paragraph).append('\n');
        }
        if (current.length() > prefix.length()) {
            chunks.add(new Chunk(sectionNo, title, partNo, current.toString(), sha256(current.toString())));
        }
    }

    /** 按空行分段。表格内部没有空行，所以整张表天然落在一个段落里，不会被拆开 */
    private static List<String> splitParagraphs(String body) {
        List<String> paragraphs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (line.isBlank()) {
                if (!current.isEmpty()) {
                    paragraphs.add(current.toString().strip());
                    current.setLength(0);
                }
            } else {
                current.append(line).append('\n');
            }
        }
        if (!current.isEmpty()) {
            paragraphs.add(current.toString().strip());
        }
        return paragraphs;
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然提供 SHA-256，走到这里说明运行环境被裁剪过
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
