package com.lazybone.hazmat.msds.ai;

import com.lazybone.hazmat.dto.MsdsHit;

import java.util.List;

/**
 * 生成式回答的模型客户端。
 *
 * 单独抽成接口有两个目的：一是没有配置生成模型时，服务层可以直接走抽取式降级；
 * 二是测试里能替换成 mock，验证「模型没给引用就退回原文」这条防线，
 * 不需要真的连一个模型。
 */
public interface ChatAnswerClient {

    /**
     * 单次生成最多喂给模型的片段数。回答里的 {@code [n]} 只能落在这个范围内：
     * 模型看不到第 6 条以后的内容，越界引用无法核对，必须整条作废。
     */
    int MAX_HITS = 5;

    /** 当前是否配置了可用的生成模型 */
    boolean available();

    /**
     * 根据问题与检索片段生成回答。模型只能使用传入片段，回答需带 {@code [n]} 引用。
     *
     * @throws AiUnavailableException 调用失败、超时或返回体不可解析
     */
    String answer(String question, List<MsdsHit> hits);
}
