package com.lazybone.hazmat.msds.knowledge;

/**
 * 检索块的统一形状。三类知识源切法不同，但落库与检索只需要这几项。
 *
 * @param sectionNo    MSDS 的节号（1–16）；国标/SOP 没有节号，填 0
 * @param sectionTitle 节标题或条款标题，用于结果展示
 * @param clauseNo     条款号（如 5.2.1）或 SOP 步骤号；MSDS 为空
 * @param partNo       同一节/条款被再切时的序号，从 0 开始
 * @param content      带「§节号 标题」或条款号前缀的正文
 * @param textHash     SHA-256，增量重建按它复用旧块与旧向量
 */
public record TextChunk(int sectionNo,
                        String sectionTitle,
                        String clauseNo,
                        int partNo,
                        String content,
                        String textHash) {
}
