package com.lazybone.hazmat.msds.ai;

import java.util.List;

/**
 * 把文本变成向量。做成接口是为了让「远程模型」和「本地哈希」可互换：
 * 没有 key 时后者保证索引仍能建成，阶段 3 的向量路才有基线可比。
 */
public interface EmbeddingClient {

    /**
     * 一次算一批，返回顺序与入参一一对应。
     *
     * @throws AiUnavailableException 调用失败（超时、鉴权、限流、返回格式不对）
     */
    List<float[]> embed(List<String> texts);

    int dimensions();

    /** 判「算找到了」的最低余弦。阶段 3 标定前返回 0，表示不过滤 */
    double minScore();

    /** 写进 vector 字段的模型名。换模型后旧向量靠它识别为脏数据 */
    String name();
}
